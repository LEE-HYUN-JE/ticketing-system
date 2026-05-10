# Tasks: Queue Admission

**Input**: `/specs/001-queue-admission/` 아래의 설계 문서  
**Prerequisites**: `plan.md`, `spec.md`, `research.md`, `data-model.md`, `contracts/queue-api.yaml`, `quickstart.md`

**Tests**: 이 기능은 대기열 순서 보장, 중복 진입 방지, Redis-only queue path, admission rate 제한, TTL 만료를 검증해야 하므로 테스트 작업을 포함한다.

**Organization**: 각 작업은 user story 단위로 묶어 독립적으로 구현하고 검증할 수 있게 구성한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 서로 다른 파일을 수정하고 미완료 작업에 의존하지 않아 병렬 진행 가능
- **[Story]**: `spec.md`의 user story와 연결
- 모든 task는 명확한 대상 파일 경로를 포함한다

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 모든 story가 의존하는 Java 21 Spring Boot 프로젝트 골격, Redis 실행 환경, 테스트 기반을 만든다.

- [X] T001 `build.gradle`에 Java 21, Spring Boot, Spring Web, Spring Data Redis, Validation, Actuator, Spring Boot Test, AssertJ, Testcontainers Redis 의존성을 설정한다
- [X] T002 [P] `settings.gradle`에 `ticketing-system` Gradle 프로젝트 설정을 추가한다
- [X] T003 [P] `src/main/java/com/example/ticketing/TicketingSystemApplication.java`에 Spring Boot 애플리케이션 진입점을 만든다
- [X] T004 [P] `src/main/resources/application.yml`에 Redis, queue 기본값, actuator, server 설정을 추가한다
- [X] T005 [P] `src/test/resources/application-test.yml`에 테스트 profile Redis 설정과 deterministic queue 기본값을 추가한다
- [X] T006 [P] `docker-compose.yml`에 로컬 개발용 Redis 서비스를 추가한다
- [X] T007 [P] `src/main/java/com/example/ticketing/queue/.gitkeep`에 queue package placeholder를 만들고 이후 작업에서 `common/config`, `common/error`, `queue/api`, `queue/application`, `queue/domain`, `queue/infrastructure` 패키지 파일을 생성할 수 있게 한다

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 user story 전에 필요한 공통 설정, 에러 처리, Redis 접근 계층, 테스트 지원 코드를 만든다.

**CRITICAL**: 이 phase가 끝나기 전에는 user story 구현을 시작하지 않는다.

- [X] T008 `src/main/java/com/example/ticketing/queue/application/QueueProperties.java`에 `admissionRatePerSecond`, `pollAfterSeconds`, `activeTtlSeconds`, `tokenTtlSeconds`를 가진 typed queue configuration properties를 구현한다
- [X] T009 `src/main/java/com/example/ticketing/common/config/RedisConfig.java`에 Lettuce 기반 `RedisTemplate`과 `StringRedisTemplate` 설정을 구현한다
- [X] T010 [P] `src/main/java/com/example/ticketing/common/error/ErrorResponse.java`에 API error response DTO를 구현한다
- [X] T011 `src/main/java/com/example/ticketing/common/error/GlobalExceptionHandler.java`에 validation error와 bad request 예외 처리를 구현한다
- [X] T012 [P] `src/main/java/com/example/ticketing/queue/domain/QueueStatus.java`에 `WAITING`, `ENTERED`, `EXPIRED` queue state enum을 정의한다
- [X] T013 [P] `src/main/java/com/example/ticketing/queue/domain/QueueModels.java`에 `QueueEntry`, `QueueTokenMapping`, `ActiveAdmission`, `QueuePosition` domain record를 정의한다
- [X] T014 `src/main/java/com/example/ticketing/queue/infrastructure/QueueRedisKeys.java`에 waiting, queue-token, active key naming helper를 구현한다
- [X] T015 `src/test/java/com/example/ticketing/support/RedisIntegrationTestSupport.java`에 Testcontainers Redis base support와 dynamic Spring Redis property 설정을 구현한다
- [X] T016 [P] `src/test/java/com/example/ticketing/support/NoDatabaseContextTest.java`에 datasource 없이 애플리케이션 context가 뜨는 no-MySQL guard test를 추가한다

**Checkpoint**: foundation이 준비되면 user story 구현을 시작할 수 있다.

---

## Phase 3: User Story 1 - 대기열 진입 (Priority: P1) MVP

**Goal**: 사용자가 이벤트에 진입하면 UUID queue token을 받고, 이벤트별 Redis Sorted Set에 요청 시각 기준으로 한 번만 등록된다.

**Independent Test**: 하나의 이벤트에 여러 진입 요청과 중복 event/user 요청을 보내고, queue token 반환, 중복 waiting position 방지, oldest-first 순서, MySQL 의존성 없음이 만족되는지 확인한다.

### Tests for User Story 1

- [X] T017 [P] [US1] `src/test/java/com/example/ticketing/queue/integration/QueueEntryApiTest.java`에 `POST /api/events/{eventId}/queue` 성공과 validation error controller contract test를 추가한다
- [X] T018 [P] [US1] `src/test/java/com/example/ticketing/queue/infrastructure/RedisQueueRepositoryTest.java`에 중복 event/user 진입, `queue-user-token:{eventId}:{userId}` reverse index, `queue-events` registry, oldest-first Sorted Set 정렬 Redis 통합 테스트를 추가한다
- [X] T019 [P] [US1] `src/test/java/com/example/ticketing/queue/application/QueueEntryServiceTest.java`에 token 발급, 기존 waiting state 재사용, rank 계산 service test를 추가한다

### Implementation for User Story 1

- [X] T020 [P] [US1] `src/main/java/com/example/ticketing/queue/api/QueueEntryDtos.java`에 queue entry request/response DTO를 구현한다
- [X] T021 [US1] `src/main/java/com/example/ticketing/queue/infrastructure/RedisQueueRepository.java`에 token mapping 생성, `queue-user-token:{eventId}:{userId}` reverse index 저장/조회, `queue-events` event registry 등록, `waiting:{eventId}` idempotent 사용자 추가, 기존 token mapping 조회, rank 계산, waiting count 조회 메서드를 구현한다
- [X] T022 [US1] `src/main/java/com/example/ticketing/queue/application/QueueEntryService.java`에 UUID queue token 발급, event/user 중복 waiting position 방지, `WAITING` 상태와 rank/totalWaiting/pollAfterSeconds 반환 로직을 구현한다
- [X] T023 [US1] `src/main/java/com/example/ticketing/queue/api/QueueController.java`에 eventId와 userId validation을 포함한 `POST /api/events/{eventId}/queue` endpoint를 구현한다
- [X] T024 [US1] `src/main/java/com/example/ticketing/TicketingSystemApplication.java`에 queue configuration properties binding을 연결하고 기본값 검증이 가능하게 한다

**Checkpoint**: User Story 1은 독립적으로 동작하고 테스트 가능해야 한다.

---

## Phase 4: User Story 2 - 대기 상태 조회 (Priority: P2)

**Goal**: 사용자는 queue token으로 polling하여 `WAITING`, `ENTERED`, `EXPIRED` 상태와 각 상태에 맞는 필드를 받을 수 있다.

**Independent Test**: 사용자를 등록하고 queue token으로 조회한다. Redis에 active/expired 상태를 준비한 뒤 OpenAPI contract에 맞는 response body와 invalid token 응답을 확인한다.

### Tests for User Story 2

- [ ] T025 [P] [US2] `src/test/java/com/example/ticketing/queue/integration/QueueStatusApiTest.java`에 `GET /api/events/{eventId}/queue/{queueToken}`의 `WAITING`, `ENTERED`, `EXPIRED`, invalid UUID, event mismatch controller contract test를 추가한다
- [ ] T026 [P] [US2] `src/test/java/com/example/ticketing/queue/infrastructure/RedisQueueStatusRepositoryTest.java`에 token lookup, active TTL lookup, missing token terminal state, expired token terminal state Redis 통합 테스트를 추가한다
- [ ] T027 [P] [US2] `src/test/java/com/example/ticketing/queue/application/QueueStatusServiceTest.java`에 WAITING rank fields, ENTERED activeExpiresInSeconds, EXPIRED terminal response service test를 추가한다

### Implementation for User Story 2

- [ ] T028 [P] [US2] `src/main/java/com/example/ticketing/queue/api/QueueStatusDtos.java`에 queue status polling response DTO를 구현한다
- [ ] T029 [US2] `src/main/java/com/example/ticketing/queue/infrastructure/RedisQueueRepository.java`에 token lookup, waiting membership check, active admission TTL lookup, terminal expired-state detection 메서드를 추가한다
- [ ] T030 [US2] `src/main/java/com/example/ticketing/queue/application/QueueStatusService.java`에 queue token을 해석하여 `WAITING`, `ENTERED`, `EXPIRED` response를 생성하는 로직을 구현한다
- [ ] T031 [US2] `src/main/java/com/example/ticketing/queue/api/QueueController.java`에 UUID parsing과 event mismatch handling을 포함한 `GET /api/events/{eventId}/queue/{queueToken}` endpoint를 추가한다

**Checkpoint**: User Story 1과 2는 public API를 통해 독립적으로 동작해야 한다.

---

## Phase 5: User Story 3 - 제한된 속도로 사용자 입장 (Priority: P3)

**Goal**: scheduler는 오래 기다린 사용자부터 설정된 초당 rate만큼 active 상태로 전환하고, TTL이 있는 active admission key를 만든다.

**Independent Test**: configured rate보다 많은 사용자를 waiting queue에 넣고 scheduler tick을 한 번 실행한다. configured rate를 초과하지 않는지, oldest-first인지, active key가 TTL 이후 만료되는지, 빈 queue에서도 정상 종료되는지 확인한다.

### Tests for User Story 3

- [ ] T032 [P] [US3] `src/test/java/com/example/ticketing/queue/application/AdmissionSchedulerServiceTest.java`에 rate limit, oldest-first admission, less-than-rate queue, empty queue scheduler service test를 추가한다
- [ ] T033 [P] [US3] `src/test/java/com/example/ticketing/queue/infrastructure/RedisAdmissionRepositoryTest.java`에 atomic pop-to-active 동작과 active admission TTL 만료 Redis 통합 테스트를 추가한다
- [ ] T034 [P] [US3] `src/test/java/com/example/ticketing/queue/integration/AdmissionFlowIntegrationTest.java`에 사용자 진입, admission 실행, polling을 통한 ENTERED 상태 관찰 end-to-end 통합 테스트를 추가한다

### Implementation for User Story 3

- [ ] T035 [P] [US3] `src/main/resources/lua/admit_waiting_users.lua`에 Redis `KEYS` 없이 오래 기다린 waiting user를 꺼내 active key를 생성하는 Lua script를 추가한다
- [ ] T036 [US3] `src/main/java/com/example/ticketing/queue/infrastructure/RedisAdmissionRepository.java`에 단일 event에 대해 `admit_waiting_users.lua`를 실행하고 admitted users를 반환하는 Redis admission repository를 구현한다
- [ ] T037 [US3] `src/main/java/com/example/ticketing/queue/application/AdmissionSchedulerService.java`에 configurable rate, active TTL, oldest-first behavior, empty-queue no-op 처리를 구현한다
- [ ] T038 [US3] `src/main/java/com/example/ticketing/queue/application/AdmissionScheduler.java`에 configured event ids 또는 Redis `KEYS`를 사용하지 않는 방식의 queue event 목록을 대상으로 scheduled admission runner를 구현한다
- [ ] T039 [US3] `src/main/java/com/example/ticketing/queue/application/QueueMetricsService.java`에 registered, admitted, currentWaiting, currentActive, expiredLookup count를 테스트와 진단에서 조회할 수 있는 admission counter service를 구현한다
- [ ] T040 [P] [US3] `src/test/java/com/example/ticketing/queue/application/ActiveAdmissionGuardTest.java`에 active admission이 있는 event/user만 예매 가능으로 판정하고 missing/expired active admission은 거부하는 service test를 추가한다
- [ ] T041 [US3] `src/main/java/com/example/ticketing/queue/application/ActiveAdmissionGuard.java`에 후속 예매 기능이 재사용할 active admission 검증 컴포넌트를 구현한다

**Checkpoint**: 모든 user story가 독립적으로 동작해야 한다.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 문서, smoke test 준비, 전체 story 검증을 마무리한다.

- [ ] T042 [P] `k6-load-test/queue-admission.js`에 100 VU smoke preset과 30,000 VU queue-only preset을 가진 부하 테스트 스크립트를 추가한다
- [ ] T043 [P] `docs/load-test-plan.md`에 100 virtual user smoke test, 30,000 virtual user queue-only test, 로컬 머신 조건 기록 항목, admission rate 20/300 users/s 검증 절차를 추가한다
- [ ] T044 [P] `docs/architecture.md`에 Redis key model, `queue-user-token:{eventId}:{userId}` reverse index, `queue-events` registry, no-MySQL queue path, scheduler behavior, active admission guard, future reservation boundary를 반영한다
- [ ] T045 `specs/001-queue-admission/quickstart.md`에 WAITING, ENTERED, EXPIRED 상태별 curl 검증 명령과 기대 응답을 추가한다
- [ ] T046 `src/test/java/com/example/ticketing/` 테스트를 대상으로 `./gradlew test`를 실행하고 실패를 수정한다
- [ ] T047 `docker compose up -d redis`를 실행한 뒤 `specs/001-queue-admission/quickstart.md`의 manual API flow를 검증한다
- [ ] T048 `src/main/java/com/example/ticketing/queue/`의 Redis access path를 검토하여 queue registration, status lookup, scheduler, active admission guard가 Redis `KEYS`와 MySQL을 사용하지 않는지 확인한다

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 의존성 없이 바로 시작 가능
- **Foundational (Phase 2)**: Setup 완료 후 진행하며 모든 user story를 block
- **User Stories (Phase 3+)**: Foundational 완료 후 진행
- **Polish (Phase 6)**: 선택한 모든 user story 완료 후 진행

### User Story Dependencies

- **User Story 1 (P1)**: Foundational 이후 시작 가능하며 MVP 범위
- **User Story 2 (P2)**: Foundational 이후 시작 가능하다. seeded Redis state로 독립 구현할 수 있지만, US1 이후 public API 검증이 가장 명확하다.
- **User Story 3 (P3)**: Foundational 이후 시작 가능하다. seeded Redis waiting entry로 독립 구현한 뒤 US1, US2와 통합한다.

### Within Each User Story

- 테스트를 먼저 작성하고, 아직 구현되지 않은 behavior 때문에 실패하는지 확인한다.
- DTO와 domain/repository operation을 service보다 먼저 구현한다.
- service를 controller endpoint나 scheduled runner보다 먼저 구현한다.
- 다음 priority story로 넘어가기 전에 checkpoint validation을 완료한다.

---

## Parallel Opportunities

- T001이 시작된 뒤 T002-T007은 서로 다른 파일을 수정하므로 병렬 진행 가능하다.
- package structure가 준비된 뒤 T010, T012, T013, T016은 병렬 진행 가능하다.
- US1 테스트 T017-T019는 병렬 진행 가능하며, T020도 repository/service 구현 준비와 병렬 진행 가능하다.
- US2 테스트 T025-T027과 DTO 작업 T028은 US1 contract 이해 후 병렬 진행 가능하다.
- US3 테스트 T032-T034와 Lua script 작업 T035는 foundational Redis support 이후 병렬 진행 가능하다.
- Active admission guard test T040은 scheduler active key 구현 이후 T041 구현과 테스트 우선 흐름으로 진행할 수 있다.
- 문서 polish 작업 T043과 T044는 behavior가 안정된 뒤 병렬 진행 가능하다.

## Parallel Example: User Story 1

```bash
Task: "T017 [P] [US1] POST /api/events/{eventId}/queue controller contract test 작성"
Task: "T018 [P] [US1] duplicate entry와 oldest-first Sorted Set Redis integration test 작성"
Task: "T019 [P] [US1] token issuance, existing waiting state reuse, rank calculation service test 작성"
```

## Parallel Example: User Story 2

```bash
Task: "T025 [P] [US2] GET /api/events/{eventId}/queue/{queueToken} controller contract test 작성"
Task: "T026 [P] [US2] token lookup, active TTL lookup, expired state Redis integration test 작성"
Task: "T027 [P] [US2] WAITING, ENTERED, EXPIRED service test 작성"
```

## Parallel Example: User Story 3

```bash
Task: "T032 [P] [US3] scheduler service test 작성"
Task: "T033 [P] [US3] atomic pop-to-active와 active TTL Redis integration test 작성"
Task: "T035 [P] [US3] admit_waiting_users.lua 작성"
Task: "T040 [P] [US3] active admission guard service test 작성"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1 Setup을 완료한다.
2. Phase 2 Foundational을 완료한다.
3. Phase 3 User Story 1을 완료한다.
4. T017-T019와 수동 `POST /api/events/{eventId}/queue` 요청으로 MVP를 검증한다.

### Incremental Delivery

1. Spring Boot 앱과 Redis test harness가 실행되도록 Setup + Foundational을 먼저 전달한다.
2. US1을 전달하여 reservation system이 직접 traffic spike를 받지 않도록 보호한다.
3. US2를 전달하여 client가 downstream reservation API를 호출하지 않고 queue state를 polling할 수 있게 한다.
4. US3을 전달하여 controlled admission이 waiting user를 TTL-bound active user로 전환하게 한다.
5. active admission guard로 후속 reservation 기능의 진입 계약을 고정한다.
6. 문서, smoke/load test, no-KEYS/no-MySQL verification을 마무리한다.

### Parallel Team Strategy

1. 한 명은 setup과 foundational Redis support를 담당한다.
2. Phase 2 이후에는 story별로 test, service, repository, scheduler 파일을 나누어 작업할 수 있다.
3. 통합 순서는 US1, US2, US3 순서로 유지한다.
