# Tasks: 비동기 예매 영속화

**Input**: Design documents from `specs/004-async-persistence/`
**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [quickstart.md](./quickstart.md)

**Tests**: constitution의 "테스트 기반 증거" 원칙에 따라 테스트 태스크를 구현 태스크 앞에 둔다. 구현 전에 해당 테스트가 실패하는지 먼저 확인한다.

**Organization**: 태스크는 user story별로 독립 구현/검증 가능하도록 나눈다.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: MySQL 의존성, Docker, 스키마, 설정, 테스트 지원 클래스를 추가한다.

- [X] T001 Add `spring-boot-starter-data-jpa`, `mysql-connector-j`, `testcontainers:mysql` dependencies in `build.gradle`
- [X] T002 Add MySQL service (image: mysql:8.0, port 3306, MYSQL_DATABASE: ticketing) in `docker-compose.yml`
- [X] T003 [P] Create `src/main/resources/schema.sql` with `reservations` table DDL (id, event_id, user_id, seat_id, status, reserved_at, idempotency_key, created_at, uk_reservation_event_user, uk_reservation_event_seat, idx_reservation_event_id)
- [X] T004 [P] Add datasource, jpa (ddl-auto: validate, open-in-view: false) config in `src/main/resources/application.yml`
- [X] T005 [P] Add Testcontainers MySQL datasource (ddl-auto: create-drop) config in `src/test/resources/application-test.yml`
- [X] T006 [P] Create `ReservationPersistenceProperties` record with `streamKey`, `consumerGroup`, `consumerName`, `batchSize`, `pendingIdleMs` fields and `@ConfigurationProperties("reservation.persistence")` in `src/main/java/com/example/ticketing/reservation/persistence/ReservationPersistenceProperties.java`
- [X] T007 Add `reservation.persistence.*` config values in `src/main/resources/application.yml`
- [X] T008 [P] Create `MysqlIntegrationTestSupport` abstract class with `@Testcontainers` + MySQL container + `@DynamicPropertySource` datasource override in `src/test/java/com/example/ticketing/support/MysqlIntegrationTestSupport.java`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 user story가 공유하는 JPA 엔티티, Repository, 이벤트 발행 인터페이스를 먼저 만든다.

**CRITICAL**: 이 단계가 끝나기 전에는 user story 구현을 시작하지 않는다.

- [X] T009 Add `ReservationEvent` record (reservationId, eventId, userId, seatId, status, reservedAt, idempotencyKey) to `src/main/java/com/example/ticketing/reservation/domain/ReservationModels.java`
- [X] T010 Create `ReservationEntity` JPA entity with `@Table(uniqueConstraints = uk_reservation_event_user, uk_reservation_event_seat)` and all fields in `src/main/java/com/example/ticketing/reservation/persistence/ReservationEntity.java`
- [X] T011 Create `ReservationJpaRepository` interface extending `JpaRepository<ReservationEntity, String>` in `src/main/java/com/example/ticketing/reservation/persistence/ReservationJpaRepository.java`
- [X] T012 [P] Create `ReservationEventPublisher` component with `publish(ReservationEvent)` method using `RedisTemplate` XADD to stream key in `src/main/java/com/example/ticketing/reservation/persistence/ReservationEventPublisher.java`
- [X] T013 [P] Enable `@EnableScheduling` in `src/main/java/com/example/ticketing/common/config/PersistenceWorkerConfig.java`

**Checkpoint**: JPA 컨텍스트가 정상 기동되고 Stream 발행 구조가 준비된다.

---

## Phase 3: User Story 1 - 예매 성공 결과 영구 저장 (Priority: P1) MVP

**Goal**: 좌석 선점 성공 시 이벤트가 Redis Stream에 발행되고, Worker가 이를 소비해 MySQL에 저장한다.

**Independent Test**: active admission 사용자로 좌석 예매를 성공시킨 뒤 잠시 기다리면 DB에 해당 예매 내역이 저장된다. 동시에 여러 사용자가 예매해도 DB 저장 수는 Redis 성공 수와 일치한다.

### Tests for User Story 1

- [X] T014 [P] [US1] Add `ReservationEventPublisherTest` — RESERVED 결과 후 `XADD` 가 호출되는지, 발행 실패 시 예외가 전파되지 않는지 검증 in `src/test/java/com/example/ticketing/reservation/persistence/ReservationEventPublisherTest.java`
- [X] T015 [P] [US1] Add `ReservationPersistenceWorkerTest` — Stream에 이벤트를 직접 XADD한 후 Worker가 소비해 DB에 저장되는지, XACK 처리되는지 검증 (RedisIntegrationTestSupport + MysqlIntegrationTestSupport) in `src/test/java/com/example/ticketing/reservation/persistence/ReservationPersistenceWorkerTest.java`
- [X] T016 [P] [US1] Add `ReservationPersistenceApiTest` — 예매 API 호출 성공 후 일정 시간 내 DB에 예매 내역이 저장되는지 E2E 검증 in `src/test/java/com/example/ticketing/reservation/integration/ReservationPersistenceApiTest.java`

### Implementation for User Story 1

- [X] T017 [US1] Inject `ReservationEventPublisher` into `SeatReservationService` and call `publish()` after `RESERVED` result in `src/main/java/com/example/ticketing/reservation/application/SeatReservationService.java`
- [X] T018 [US1] Implement `ReservationPersistenceWorker` with `@Scheduled(fixedDelay = 100)`: consumer group creation on startup (XGROUP CREATE MKSTREAM), XREADGROUP BLOCK 1000 COUNT batchSize, convert message to `ReservationEntity`, JPA save, `DataIntegrityViolationException` → warn + skip, XACK in `src/main/java/com/example/ticketing/reservation/persistence/ReservationPersistenceWorker.java`
- [X] T019 [US1] Run `./gradlew test --tests '*Persistence*'` and fix US1 regressions

**Checkpoint**: 예매 → Stream 발행 → Worker 소비 → DB 저장 흐름이 독립적으로 검증된다.

---

## Phase 4: User Story 2 - 정합성 검증 가능 (Priority: P2)

**Goal**: DB에 중복 좌석/중복 사용자가 저장되지 않으며, 정합성 쿼리로 검증 가능하다.

**Independent Test**: 동일 사용자가 두 번 예매 시도해도 DB에는 1건만 저장된다. 동일 좌석을 두 사용자가 선점 시도해도 DB에는 1건만 저장된다.

### Tests for User Story 2

- [X] T020 [P] [US2] Add `ReservationEntityConstraintTest` — 동일 (event_id, user_id)로 두 번 save 시 `DataIntegrityViolationException` 발생, 동일 (event_id, seat_id)도 동일하게 검증 in `src/test/java/com/example/ticketing/reservation/persistence/ReservationEntityConstraintTest.java`
- [X] T021 [P] [US2] Add consistency scenario to `ReservationPersistenceApiTest` — 같은 사용자가 idempotency key를 다르게 하여 두 번 예매 API를 호출해도 DB에 1건만 저장되는지 검증

### Implementation for User Story 2

- [X] T022 [US2] Verify unique constraint on `ReservationEntity` covers (event_id, user_id) and (event_id, seat_id), update if needed in `src/main/java/com/example/ticketing/reservation/persistence/ReservationEntity.java`
- [X] T023 [US2] Run `./gradlew test --tests '*Persistence*'` and fix US2 regressions

**Checkpoint**: unique constraint가 DB 레벨에서 중복을 막으며, Worker는 중복 저장 시도를 안전하게 처리한다.

---

## Phase 5: User Story 3 - Worker 중단 시 이벤트 유실 없음 (Priority: P3)

**Goal**: Worker가 이벤트를 소비한 뒤 XACK 전 중단되어도 재시작 시 재처리한다.

**Independent Test**: Stream에 이벤트를 추가하고 Worker가 XREADGROUP으로 소유권을 가진 직후 ACK 없이 중단 시뮬레이션 → Worker 재시작 → XAUTOCLAIM으로 해당 이벤트 재처리 → DB 저장 확인.

### Tests for User Story 3

- [X] T024 [P] [US3] Add pending message reprocessing test to `ReservationPersistenceWorkerTest` — Stream에 메시지를 XADD하고 XREADGROUP으로 읽기만 한 후(ACK 없음), Worker의 XAUTOCLAIM 로직이 idle 초과 메시지를 재소유해 DB에 저장하는지 검증

### Implementation for User Story 3

- [X] T025 [US3] Add XAUTOCLAIM step before XREADGROUP in `ReservationPersistenceWorker.processLoop()` — idle > `pendingIdleMs` 메시지를 재소유해 처리 in `src/main/java/com/example/ticketing/reservation/persistence/ReservationPersistenceWorker.java`
- [X] T026 [US3] Run `./gradlew test --tests '*Persistence*'` and fix US3 regressions

**Checkpoint**: 모든 User Story가 독립적으로 검증 가능하다.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 전체 회귀, 문서 정합성, 산출물 마무리.

- [X] T027 [P] Verify `quickstart.md` consistency query examples match actual schema in `specs/004-async-persistence/quickstart.md`
- [X] T028 [P] Verify `data-model.md` DDL matches `ReservationEntity` annotations in `specs/004-async-persistence/data-model.md`
- [X] T029 Run full test suite with `./gradlew test`
- [X] T030 Mark completed tasks in `specs/004-async-persistence/tasks.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 즉시 시작 가능
- **Foundational (Phase 2)**: Setup 완료 후 진행, 모든 user story를 block
- **User Story 1 (Phase 3)**: Foundational 완료 후 진행, MVP
- **User Story 2 (Phase 4)**: Foundational 완료 후 독립 시작 가능 (순차 권장)
- **User Story 3 (Phase 5)**: Foundational 완료 후 독립 시작 가능 (순차 권장)
- **Polish (Phase 6)**: 선택한 user story 구현 완료 후 진행

### User Story Dependencies

- **US1**: Foundational만 필요
- **US2**: Foundational만 필요. unique constraint 검증은 US1의 Worker 구현이 있으면 더 명확
- **US3**: Foundational + US1 Worker 구현 필요 (XAUTOCLAIM은 Worker 기반)

### Within Each User Story

- 테스트 태스크를 먼저 작성하고 실패를 확인한다.
- Publisher → Service 수정 → Worker 순서로 구현한다.
- 각 story checkpoint에서 `./gradlew test --tests '*Persistence*'`를 실행한다.

### Parallel Opportunities

- T003, T004, T005, T006, T008은 서로 다른 파일이라 병렬 가능하다.
- T012, T013은 서로 다른 파일이라 병렬 가능하다.
- T014, T015, T016은 서로 다른 파일이라 병렬 가능하다.
- T020, T021은 서로 다른 파일이라 병렬 가능하다.
- T027, T028은 서로 다른 파일이라 병렬 가능하다.

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational
3. Complete Phase 3: User Story 1
4. Stop and validate with `./gradlew test --tests '*Persistence*'`
5. Commit the MVP if tests pass

### Incremental Delivery

1. Setup + Foundational 완료
2. US1 구현 및 검증
3. US2 구현 및 검증
4. US3 구현 및 검증
5. Polish 단계에서 문서와 전체 테스트 확인

### Suggested Commit Slices

- Commit 1: T001-T008 setup/infrastructure
- Commit 2: T009-T013 foundational entities/publisher
- Commit 3: T014-T019 US1 stream→DB persistence MVP
- Commit 4: T020-T023 US2 constraint validation
- Commit 5: T024-T026 US3 pending reprocess
- Commit 6: T027-T030 polish and full validation

---

## Notes

- `[P]` 태스크는 서로 다른 파일이거나 완료되지 않은 태스크에 의존하지 않아 병렬 처리 가능함을 의미한다.
- `[US1]`, `[US2]`, `[US3]` label은 spec.md의 user story와 매핑된다.
- Worker의 `DataIntegrityViolationException` 처리는 중복 저장 시도를 정상 경로로 간주하고 XACK한다.
- 이벤트 발행 실패는 예매 응답에 영향을 주지 않는다 (warn 로그만).
- `ddl-auto: validate` 운영 설정을 위해 `schema.sql`로 스키마를 별도 관리한다.
