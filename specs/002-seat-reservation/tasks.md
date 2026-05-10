# Tasks: Seat Reservation

**Input**: `/specs/002-seat-reservation/` 아래의 설계 문서  
**Prerequisites**: `plan.md`, `spec.md`, `research.md`, `data-model.md`, `contracts/reservation-api.yaml`, `quickstart.md`

**Tests**: 이 기능은 active admission 검증, 좌석 선점 원자성, 같은 좌석 중복 선점 방지, 동일 사용자 중복 예매 방지, 결과 조회를 검증해야 하므로 테스트 작업을 포함한다.

**Organization**: 각 작업은 user story 단위로 묶어 독립적으로 구현하고 검증할 수 있게 구성한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 서로 다른 파일을 수정하고 미완료 작업에 의존하지 않아 병렬 진행 가능
- **[Story]**: `spec.md`의 user story와 연결
- 모든 task는 명확한 대상 파일 경로를 포함한다

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: reservation package와 공통 설정을 추가한다.

- [X] T001 [P] `src/main/java/com/example/ticketing/reservation/domain/ReservationStatus.java`에 `RESERVED`, `NOT_ACTIVE`, `SEAT_ALREADY_TAKEN`, `ALREADY_RESERVED`, `NOT_RESERVED`, `INVALID_SEAT` enum을 정의한다
- [X] T002 [P] `src/main/java/com/example/ticketing/reservation/domain/ReservationModels.java`에 `ReservationClaim`, `SeatClaimResult`, `ReservationLookupResult` domain record를 정의한다
- [X] T003 [P] `src/main/java/com/example/ticketing/reservation/application/ReservationProperties.java`에 seat capacity 기본값 2000과 seat id prefix 설정을 추가한다
- [X] T004 `src/main/resources/application.yml`과 `src/test/resources/application-test.yml`에 reservation seat capacity와 seat id prefix 기본값을 추가한다
- [X] T005 [P] `src/main/java/com/example/ticketing/reservation/infrastructure/ReservationRedisKeys.java`에 seat, reservation user result key helper를 구현한다

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 좌석 id 검증, Redis Lua 실행 기반, 테스트 지원 코드를 만든다.

**CRITICAL**: 이 phase가 끝나기 전에는 user story 구현을 시작하지 않는다.

- [X] T006 `src/main/java/com/example/ticketing/reservation/application/SeatIdValidator.java`에 `seat-1`부터 `seat-2000` 범위 검증을 구현한다
- [X] T007 [P] `src/test/java/com/example/ticketing/reservation/application/SeatIdValidatorTest.java`에 정상 seat id, 0번 좌석, 범위 초과, 잘못된 형식 검증 테스트를 추가한다
- [X] T008 `src/main/resources/lua/claim_seat.lua`에 active admission, 기존 사용자 예약, 좌석 점유 여부를 검사하고 결과를 원자적으로 기록하는 Lua script skeleton을 추가한다

**Checkpoint**: foundation이 준비되면 user story 구현을 시작할 수 있다.

---

## Phase 3: User Story 1 - 좌석 선점 성공 (Priority: P1) MVP

**Goal**: active admission을 가진 사용자가 비어 있는 좌석을 RESERVED로 선점한다.

**Independent Test**: active key를 준비한 뒤 좌석 예매 요청을 보내 RESERVED 응답과 Redis seat/user reservation result가 남는지 확인한다.

### Tests for User Story 1

- [X] T009 [P] [US1] `src/test/java/com/example/ticketing/reservation/integration/ReservationApiTest.java`에 active 사용자 좌석 선점 성공, NOT_ACTIVE, invalid seat controller contract test를 추가한다
- [X] T010 [P] [US1] `src/test/java/com/example/ticketing/reservation/infrastructure/RedisReservationRepositoryTest.java`에 active admission 기반 RESERVED 기록과 NOT_ACTIVE 결과 Redis 통합 테스트를 추가한다
- [X] T011 [P] [US1] `src/test/java/com/example/ticketing/reservation/application/SeatReservationServiceTest.java`에 RESERVED 응답, NOT_ACTIVE 응답, invalid seat 응답 service test를 추가한다

### Implementation for User Story 1

- [X] T012 [P] [US1] `src/main/java/com/example/ticketing/reservation/api/ReservationDtos.java`에 reservation request/response DTO를 구현한다
- [X] T013 [US1] `src/main/java/com/example/ticketing/reservation/infrastructure/RedisReservationRepository.java`에 `claim_seat.lua` 실행과 result mapping을 구현한다
- [X] T014 [US1] `src/main/java/com/example/ticketing/reservation/application/SeatReservationService.java`에 active admission 기반 좌석 선점 application service를 구현한다
- [X] T015 [US1] `src/main/java/com/example/ticketing/reservation/api/ReservationController.java`에 `POST /api/events/{eventId}/reservations` endpoint를 구현한다

**Checkpoint**: User Story 1은 독립적으로 동작하고 테스트 가능해야 한다.

---

## Phase 4: User Story 2 - 같은 좌석 중복 선점 방지 (Priority: P2)

**Goal**: 같은 좌석에 대한 동시 요청에서 하나의 요청만 RESERVED가 된다.

**Independent Test**: active admission을 가진 여러 사용자가 같은 좌석을 동시에 요청하고, RESERVED 1건과 SEAT_ALREADY_TAKEN 나머지 결과를 확인한다.

### Tests for User Story 2

- [X] T016 [P] [US2] `src/test/java/com/example/ticketing/reservation/infrastructure/ConcurrentSeatClaimTest.java`에 같은 좌석 동시 요청 RESERVED 최대 1건 통합 테스트를 추가한다
- [X] T017 [P] [US2] `src/test/java/com/example/ticketing/reservation/application/DuplicateReservationServiceTest.java`에 같은 user가 다른 seat를 다시 요청할 때 ALREADY_RESERVED를 반환하는 service test를 추가한다

### Implementation for User Story 2

- [X] T018 [US2] `src/main/resources/lua/claim_seat.lua`에 같은 좌석이 이미 선점된 경우 SEAT_ALREADY_TAKEN을 반환하고 기존 소유자를 유지하는 분기를 완성한다
- [X] T019 [US2] `src/main/resources/lua/claim_seat.lua`에 같은 event/user가 이미 예약한 경우 ALREADY_RESERVED와 기존 seatId를 반환하는 분기를 완성한다
- [X] T020 [US2] `src/main/java/com/example/ticketing/reservation/application/SeatReservationService.java`에 SEAT_ALREADY_TAKEN과 ALREADY_RESERVED 결과 mapping을 반영한다

**Checkpoint**: User Stories 1과 2는 public API와 Redis 통합 테스트로 정합성을 검증할 수 있어야 한다.

---

## Phase 5: User Story 3 - 예매 결과 조회 (Priority: P3)

**Goal**: 사용자는 event id와 user id로 현재 예매 결과를 조회한다.

**Independent Test**: 예약 전에는 NOT_RESERVED, 예약 후에는 RESERVED와 seat id가 반환되는지 확인한다.

### Tests for User Story 3

- [X] T021 [P] [US3] `src/test/java/com/example/ticketing/reservation/integration/ReservationLookupApiTest.java`에 NOT_RESERVED와 RESERVED 조회 controller contract test를 추가한다
- [X] T022 [P] [US3] `src/test/java/com/example/ticketing/reservation/application/ReservationLookupServiceTest.java`에 사용자별 reservation result 조회 service test를 추가한다

### Implementation for User Story 3

- [X] T023 [US3] `src/main/java/com/example/ticketing/reservation/infrastructure/RedisReservationRepository.java`에 user reservation result 조회 메서드를 추가한다
- [X] T024 [US3] `src/main/java/com/example/ticketing/reservation/application/ReservationLookupService.java`에 NOT_RESERVED/RESERVED 조회 로직을 구현한다
- [X] T025 [US3] `src/main/java/com/example/ticketing/reservation/api/ReservationController.java`에 `GET /api/events/{eventId}/reservations/users/{userId}` endpoint를 추가한다

**Checkpoint**: 모든 user story가 독립적으로 동작해야 한다.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 문서와 전체 검증을 마무리한다.

- [X] T026 [P] `docs/architecture.md`에 Reservation API, Redis Lua 좌석 선점, reservation result key, no-MySQL boundary를 반영한다
- [X] T027 `specs/002-seat-reservation/quickstart.md`에 실제 curl 예시와 기대 응답을 구현 결과에 맞게 보정한다
- [X] T028 `src/test/java/com/example/ticketing/` 테스트를 대상으로 `./gradlew test`를 실행하고 실패를 수정한다
- [X] T029 `src/main/java/com/example/ticketing/reservation/`와 `src/main/resources/lua/claim_seat.lua`를 검토해 Redis `KEYS`와 MySQL 사용이 없는지 확인한다

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 현재 queue 기능 구현 위에서 바로 시작 가능
- **Foundational (Phase 2)**: Setup 완료 후 진행하며 모든 user story를 block
- **User Stories (Phase 3+)**: Foundational 완료 후 진행
- **Polish (Phase 6)**: 선택한 모든 user story 완료 후 진행

### User Story Dependencies

- **User Story 1 (P1)**: Foundational 이후 시작 가능하며 MVP 범위
- **User Story 2 (P2)**: US1의 Lua/repository/service 흐름 위에서 동시성 및 중복 예매 분기를 강화
- **User Story 3 (P3)**: US1의 Redis reservation result가 있으면 독립 구현 가능

### Within Each User Story

- 테스트를 먼저 작성하고, 아직 구현되지 않은 behavior 때문에 실패하는지 확인한다.
- Lua script와 repository mapping을 service보다 먼저 안정화한다.
- service를 controller endpoint보다 먼저 구현한다.
- 다음 priority story로 넘어가기 전에 checkpoint validation을 완료한다.

---

## Parallel Opportunities

- T001-T003과 T005는 서로 다른 파일을 수정하므로 병렬 진행 가능하다.
- T009-T011은 서로 다른 테스트 파일이므로 병렬 작성 가능하다.
- T016과 T017은 서로 다른 테스트 파일이므로 병렬 작성 가능하다.
- T021과 T022는 서로 다른 테스트 파일이므로 병렬 작성 가능하다.
- 문서 작업 T026은 구현 안정화 이후 T027과 병렬 진행 가능하다.

## Parallel Example: User Story 1

```bash
Task: "T009 [P] [US1] Reservation API contract test 작성"
Task: "T010 [P] [US1] Redis reservation repository integration test 작성"
Task: "T011 [P] [US1] SeatReservationService test 작성"
```

## Parallel Example: User Story 2

```bash
Task: "T016 [P] [US2] 같은 좌석 동시 요청 통합 테스트 작성"
Task: "T017 [P] [US2] 동일 사용자 중복 예매 service test 작성"
```

## Parallel Example: User Story 3

```bash
Task: "T021 [P] [US3] 예매 결과 조회 API test 작성"
Task: "T022 [P] [US3] ReservationLookupService test 작성"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1 Setup을 완료한다.
2. Phase 2 Foundational을 완료한다.
3. Phase 3 User Story 1을 완료한다.
4. active key를 직접 준비한 뒤 POST reservation API로 RESERVED/NOT_ACTIVE를 검증한다.

### Incremental Delivery

1. Setup + Foundational로 reservation package와 Lua 실행 기반을 만든다.
2. US1로 active 사용자 좌석 선점을 구현한다.
3. US2로 좌석/사용자 중복 선점 방지를 강화한다.
4. US3로 결과 조회를 추가한다.
5. 문서와 no-KEYS/no-MySQL 검증을 마무리한다.

### Parallel Team Strategy

1. 한 명은 Lua/repository를 담당한다.
2. 한 명은 API/service/test를 담당한다.
3. 통합 순서는 US1, US2, US3 순서로 유지한다.

