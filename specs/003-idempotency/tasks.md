# Tasks: Reservation Idempotency

**Input**: Design documents from `specs/003-idempotency/`
**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/reservation-api.yaml](./contracts/reservation-api.yaml), [quickstart.md](./quickstart.md)

**Tests**: 이 기능은 constitution의 "테스트 기반 증거" 원칙과 spec의 Independent Test를 만족해야 하므로 테스트 작업을 각 user story 앞에 둔다. 구현 전에 해당 테스트가 실패하는지 먼저 확인한다.

**Organization**: 작업은 user story별로 독립 구현/검증 가능하도록 나눈다.

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 기존 예약 기능에 idempotency 설정과 key namespace를 추가한다.

- [X] T001 Add `idempotencyTtlSeconds` and `idempotencyKeyMaxLength` fields with defaults in `src/main/java/com/example/ticketing/reservation/application/ReservationProperties.java`
- [X] T002 Add `reservation.idempotency-ttl-seconds` and `reservation.idempotency-key-max-length` config values in `src/main/resources/application.yml`
- [X] T003 [P] Add `idempotency(eventId, userId, idempotencyKey)` Redis key helper in `src/main/java/com/example/ticketing/reservation/infrastructure/ReservationRedisKeys.java`
- [X] T004 [P] Add idempotency property binding coverage in `src/test/java/com/example/ticketing/reservation/application/ReservationPropertiesTest.java`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 user story가 공유하는 method signature, Redis script interface, test fixture를 먼저 맞춘다.

**CRITICAL**: 이 단계가 끝나기 전에는 user story 구현을 시작하지 않는다.

- [X] T005 Update `SeatReservationService.claimSeat` signature to accept `idempotencyKey` in `src/main/java/com/example/ticketing/reservation/application/SeatReservationService.java`
- [X] T006 Update `RedisReservationRepository.claimSeat` signature to accept `idempotencyKey` and `idempotencyTtlSeconds` in `src/main/java/com/example/ticketing/reservation/infrastructure/RedisReservationRepository.java`
- [X] T007 Update existing reservation service tests for the new idempotency-aware method signature in `src/test/java/com/example/ticketing/reservation/application/SeatReservationServiceTest.java`
- [X] T008 Update existing Redis repository tests for the new idempotency-aware method signature in `src/test/java/com/example/ticketing/reservation/infrastructure/RedisReservationRepositoryTest.java`
- [X] T009 Update existing reservation API tests to send `Idempotency-Key` header where successful reservation is expected in `src/test/java/com/example/ticketing/reservation/integration/ReservationApiTest.java`

**Checkpoint**: 기존 좌석 예매 테스트가 새 signature와 header 요구사항을 반영할 준비가 된다.

---

## Phase 3: User Story 1 - 같은 요청 재시도 결과 재사용 (Priority: P1) MVP

**Goal**: 같은 event/user/idempotency key 반복 요청이 좌석 선점을 다시 시도하지 않고 최초 결과를 그대로 반환한다.

**Independent Test**: active admission 사용자로 같은 key 예약 요청을 두 번 보내면 두 응답이 동일하고, 같은 key로 다른 seat id를 보내도 최초 결과가 반환된다. active admission 없는 최초 `NOT_ACTIVE` 결과도 같은 key 재시도에서 재사용된다.

### Tests for User Story 1

- [X] T010 [P] [US1] Add service test for same key replay returning the original result in `src/test/java/com/example/ticketing/reservation/application/SeatReservationServiceTest.java`
- [X] T011 [P] [US1] Add Redis integration tests for same key same seat replay, same key `SEAT_ALREADY_TAKEN` replay, and idempotency hash TTL in `src/test/java/com/example/ticketing/reservation/infrastructure/RedisReservationRepositoryTest.java`
- [X] T012 [P] [US1] Add Redis integration test for same key different seat returning the first result in `src/test/java/com/example/ticketing/reservation/infrastructure/RedisReservationRepositoryTest.java`
- [X] T013 [P] [US1] Add Redis integration test for `NOT_ACTIVE` result replay in `src/test/java/com/example/ticketing/reservation/infrastructure/RedisReservationRepositoryTest.java`
- [X] T014 [P] [US1] Add API integration test for duplicate `Idempotency-Key` replay in `src/test/java/com/example/ticketing/reservation/integration/ReservationApiTest.java`

### Implementation for User Story 1

- [X] T015 [US1] Pass `Idempotency-Key` from `ReservationController.claimSeat` to the service in `src/main/java/com/example/ticketing/reservation/api/ReservationController.java`
- [X] T016 [US1] Pass idempotency key and TTL from `SeatReservationService` to repository in `src/main/java/com/example/ticketing/reservation/application/SeatReservationService.java`
- [X] T017 [US1] Include idempotency Redis key in Lua script keys and TTL argument in `src/main/java/com/example/ticketing/reservation/infrastructure/RedisReservationRepository.java`
- [X] T018 [US1] Implement idempotency result lookup and replay before new claim processing in `src/main/resources/lua/claim_seat.lua`
- [X] T019 [US1] Store `RESERVED`, `NOT_ACTIVE`, and `SEAT_ALREADY_TAKEN` results with `status`, `seatId`, `message`, `requestSeatId`, `createdAt` and TTL in `src/main/resources/lua/claim_seat.lua`
- [X] T020 [US1] Run `./gradlew test --tests '*Reservation*'` and fix US1 regressions in reservation code and tests

**Checkpoint**: User Story 1은 독립적으로 동작해야 하며 MVP로 검증 가능해야 한다.

---

## Phase 4: User Story 2 - 서로 다른 key는 별도 요청으로 처리 (Priority: P2)

**Goal**: 서로 다른 idempotency key는 별도 요청으로 처리하지만, 같은 event/user는 이미 가진 좌석 하나만 유지한다.

**Independent Test**: 같은 사용자가 key A로 seat-10을 예약한 뒤 key B로 seat-11을 요청하면 `ALREADY_RESERVED`와 seat-10을 반환하고, key B 반복 요청은 같은 `ALREADY_RESERVED` 결과를 재사용한다.

### Tests for User Story 2

- [X] T021 [P] [US2] Add service test for different key returning `ALREADY_RESERVED` after first reservation in `src/test/java/com/example/ticketing/reservation/application/SeatReservationServiceTest.java`
- [X] T022 [P] [US2] Add Redis integration test for key A `RESERVED`, key B `ALREADY_RESERVED`, key B replay in `src/test/java/com/example/ticketing/reservation/infrastructure/RedisReservationRepositoryTest.java`
- [X] T023 [P] [US2] Add API integration test for different key preserving one reserved seat per event/user in `src/test/java/com/example/ticketing/reservation/integration/ReservationApiTest.java`

### Implementation for User Story 2

- [X] T024 [US2] Store `ALREADY_RESERVED` result in the idempotency hash with TTL in `src/main/resources/lua/claim_seat.lua`
- [X] T025 [US2] Ensure different idempotency keys and same keys across different event/user combinations create distinct Redis idempotency keys in `src/main/java/com/example/ticketing/reservation/infrastructure/RedisReservationRepository.java`
- [X] T026 [US2] Run `./gradlew test --tests '*Reservation*'` and fix US2 regressions in reservation code and tests

**Checkpoint**: User Stories 1 and 2가 함께 동작하고, 같은 event/user는 좌석 하나만 가진다.

---

## Phase 5: User Story 3 - Idempotency-Key 입력 검증 (Priority: P3)

**Goal**: 좌석 예매 변경 요청에서 `Idempotency-Key` 누락, blank, 길이 초과를 명확히 거절하고 좌석 선점을 수행하지 않는다.

**Independent Test**: active admission이 있어도 `Idempotency-Key`가 없거나 blank 또는 120자 초과이면 400 응답이며 Redis seat/user reservation key가 생성되지 않는다.

### Tests for User Story 3

- [X] T027 [P] [US3] Add API integration test for missing `Idempotency-Key` returning 400 without reservation in `src/test/java/com/example/ticketing/reservation/integration/ReservationApiTest.java`
- [X] T028 [P] [US3] Add API integration test for blank `Idempotency-Key` returning 400 without reservation in `src/test/java/com/example/ticketing/reservation/integration/ReservationApiTest.java`
- [X] T029 [P] [US3] Add API integration test for overlength `Idempotency-Key` returning 400 without reservation in `src/test/java/com/example/ticketing/reservation/integration/ReservationApiTest.java`
- [X] T030 [P] [US3] Add service validation test for blank and overlength idempotency key in `src/test/java/com/example/ticketing/reservation/application/SeatReservationServiceTest.java`

### Implementation for User Story 3

- [X] T031 [US3] Add `@RequestHeader("Idempotency-Key")` validation constraints to `ReservationController.claimSeat` in `src/main/java/com/example/ticketing/reservation/api/ReservationController.java`
- [X] T032 [US3] Validate required and max length idempotency key in `SeatReservationService.claimSeat` in `src/main/java/com/example/ticketing/reservation/application/SeatReservationService.java`
- [X] T033 [US3] Ensure validation errors map to clear 400 responses through existing exception handling or controller validation in `src/main/java/com/example/ticketing/reservation/api/ReservationController.java`
- [X] T034 [US3] Run `./gradlew test --tests '*Reservation*'` and fix US3 regressions in reservation code and tests

**Checkpoint**: 모든 user story가 독립적으로 검증 가능해야 한다.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 전체 회귀, 문서 정합성, SpecKit 산출물 마무리.

- [X] T035 [P] Update quickstart examples if final error response or message differs in `specs/003-idempotency/quickstart.md`
- [X] T036 [P] Update OpenAPI contract if final validation response shape differs in `specs/003-idempotency/contracts/reservation-api.yaml`
- [X] T037 Review Redis key names and TTL behavior against `specs/003-idempotency/data-model.md`
- [X] T038 Run full test suite with `./gradlew test`
- [X] T039 Mark completed tasks in `specs/003-idempotency/tasks.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 즉시 시작 가능
- **Foundational (Phase 2)**: Setup 완료 후 진행, 모든 user story를 block
- **User Story 1 (Phase 3)**: Foundational 완료 후 진행, MVP
- **User Story 2 (Phase 4)**: Foundational 완료 후 독립 시작 가능하지만, 순차 구현 시 US1 이후가 가장 단순함
- **User Story 3 (Phase 5)**: Foundational 완료 후 독립 시작 가능하지만, controller/service signature가 안정된 뒤 구현하는 것이 좋음
- **Polish (Phase 6)**: 선택한 user story 구현 완료 후 진행

### User Story Dependencies

- **US1**: Foundational만 필요
- **US2**: Foundational만 필요. 단, 실제 검증은 US1의 `RESERVED` 저장 동작을 전제로 하면 더 명확함
- **US3**: Foundational만 필요

### Within Each User Story

- 테스트 작업을 먼저 작성하고 실패를 확인한다.
- Redis Lua script 변경은 repository key/argument 변경 이후 진행한다.
- API integration test는 controller/service/repository 변경이 연결된 뒤 통과시킨다.
- 각 story checkpoint에서 `./gradlew test --tests '*Reservation*'`를 실행한다.

### Parallel Opportunities

- T003과 T004는 서로 다른 파일이라 병렬 가능하다.
- T010부터 T014는 같은 story의 테스트 설계 작업이며 파일 충돌을 조정하면 병렬 가능하다.
- T021부터 T023은 US2 테스트 작업으로 병렬 가능하다.
- T027부터 T030은 US3 테스트 작업으로 병렬 가능하다.
- T035와 T036은 서로 다른 문서 파일이라 병렬 가능하다.

---

## Parallel Example: User Story 1

```text
Task: "Add service test for same key replay returning the original result in src/test/java/com/example/ticketing/reservation/application/SeatReservationServiceTest.java"
Task: "Add Redis integration test for same key same seat replay and idempotency hash TTL in src/test/java/com/example/ticketing/reservation/infrastructure/RedisReservationRepositoryTest.java"
Task: "Add API integration test for duplicate Idempotency-Key replay in src/test/java/com/example/ticketing/reservation/integration/ReservationApiTest.java"
```

## Parallel Example: User Story 2

```text
Task: "Add service test for different key returning ALREADY_RESERVED after first reservation in src/test/java/com/example/ticketing/reservation/application/SeatReservationServiceTest.java"
Task: "Add Redis integration test for key A RESERVED, key B ALREADY_RESERVED, key B replay in src/test/java/com/example/ticketing/reservation/infrastructure/RedisReservationRepositoryTest.java"
Task: "Add API integration test for different key preserving one reserved seat per event/user in src/test/java/com/example/ticketing/reservation/integration/ReservationApiTest.java"
```

## Parallel Example: User Story 3

```text
Task: "Add API integration test for missing Idempotency-Key returning 400 without reservation in src/test/java/com/example/ticketing/reservation/integration/ReservationApiTest.java"
Task: "Add API integration test for blank Idempotency-Key returning 400 without reservation in src/test/java/com/example/ticketing/reservation/integration/ReservationApiTest.java"
Task: "Add service validation test for blank and overlength idempotency key in src/test/java/com/example/ticketing/reservation/application/SeatReservationServiceTest.java"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational
3. Complete Phase 3: User Story 1
4. Stop and validate with `./gradlew test --tests '*Reservation*'`
5. Commit the MVP if tests pass

### Incremental Delivery

1. Setup + Foundational 완료
2. US1 구현 및 검증
3. US2 구현 및 검증
4. US3 구현 및 검증
5. Polish 단계에서 문서와 전체 테스트 확인

### Suggested Commit Slices

- Commit 1: T001-T009 setup/foundational signature updates
- Commit 2: T010-T020 US1 same-key replay MVP
- Commit 3: T021-T026 US2 different-key behavior
- Commit 4: T027-T034 US3 key validation
- Commit 5: T035-T039 polish and full validation

## Notes

- `[P]` 작업은 서로 다른 파일이거나 완료되지 않은 작업에 의존하지 않아 병렬 처리 가능함을 의미한다.
- `[US1]`, `[US2]`, `[US3]` label은 spec.md의 user story와 매핑된다.
- idempotency validation 실패는 Redis에 저장하지 않는다.
- `INVALID_SEAT`은 기존 좌석 id 검증 경로를 유지하며, 이번 핵심 idempotency 저장 대상은 `RESERVED`, `ALREADY_RESERVED`, `SEAT_ALREADY_TAKEN`, `NOT_ACTIVE`다.
