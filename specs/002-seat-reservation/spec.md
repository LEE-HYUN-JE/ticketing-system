# Feature Specification: Seat Reservation

**Feature Branch**: `002-seat-reservation`  
**Created**: 2026-05-10  
**Status**: Draft  
**Input**: User description: "좌석 예매 기능을 만든다. active admission을 받은 사용자만 특정 이벤트의 좌석을 선택해 원자적으로 선점할 수 있어야 한다. 좌석은 Redis에서 먼저 선점하고, 같은 좌석의 중복 선점과 동일 사용자의 중복 예매를 막는다. MySQL 영속화는 이번 기능에서 직접 처리하지 않고 후속 비동기 저장 기능에서 다룬다."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 좌석 선점 성공 (Priority: P1)

active admission을 받은 사용자는 특정 예매 이벤트에서 비어 있는 좌석 하나를 선택해 예매 성공 상태로 선점할 수 있다.

**Why this priority**: 이 기능은 대기열 다음 단계의 핵심이다. active admission이 실제 좌석 선점 권한으로 이어지지 않으면 queue admission 기능의 의미가 없다.

**Independent Test**: active admission을 가진 사용자가 비어 있는 좌석을 예약 요청하면 RESERVED 상태와 seat id를 받고, Redis에 좌석 소유자와 사용자별 예약 결과가 남는지 확인한다.

**Acceptance Scenarios**:

1. **Given** active admission을 가진 사용자와 비어 있는 좌석, **When** 사용자가 좌석 예매를 요청하면, **Then** 좌석은 해당 사용자에게 RESERVED 상태로 선점된다.
2. **Given** 예매 성공 후 같은 event/user 조합, **When** 사용자가 다시 좌석 예매를 요청하면, **Then** 새 좌석을 추가로 선점하지 않고 기존 예약 결과 또는 중복 예매 실패를 반환한다.
3. **Given** active admission이 없는 사용자, **When** 사용자가 좌석 예매를 요청하면, **Then** 좌석은 선점되지 않고 NOT_ACTIVE 상태가 반환된다.

---

### User Story 2 - 같은 좌석 중복 선점 방지 (Priority: P2)

여러 사용자가 같은 좌석을 동시에 선택해도 시스템은 단 한 명에게만 좌석 선점을 허용한다.

**Why this priority**: 좌석 수보다 많은 예매가 성공하지 않도록 하는 핵심 정합성 요구사항이다.

**Independent Test**: active admission을 가진 여러 사용자가 같은 event/seat에 동시에 예약 요청을 보내고, 성공 응답이 정확히 1건이며 나머지는 SEAT_ALREADY_TAKEN인지 확인한다.

**Acceptance Scenarios**:

1. **Given** 비어 있는 같은 좌석을 선택한 두 active 사용자, **When** 두 요청이 동시에 처리되면, **Then** 하나의 요청만 RESERVED가 되고 다른 요청은 SEAT_ALREADY_TAKEN이 된다.
2. **Given** 이미 선점된 좌석, **When** 다른 active 사용자가 같은 좌석을 요청하면, **Then** 기존 소유자는 변경되지 않는다.

---

### User Story 3 - 예매 결과 조회 (Priority: P3)

사용자는 event id와 user id로 자신의 현재 예매 결과를 조회할 수 있다.

**Why this priority**: 후속 비동기 영속화와 수동 검증에서 Redis에 남은 예매 결과를 관찰할 수 있어야 한다.

**Independent Test**: 좌석 선점 성공 전후로 사용자별 예약 결과를 조회하여 없음과 RESERVED 상태가 올바르게 반환되는지 확인한다.

**Acceptance Scenarios**:

1. **Given** 아직 좌석을 선점하지 않은 사용자, **When** 예매 결과를 조회하면, **Then** NOT_RESERVED 상태가 반환된다.
2. **Given** 좌석 선점에 성공한 사용자, **When** 예매 결과를 조회하면, **Then** RESERVED 상태와 seat id가 반환된다.

### Edge Cases

- active admission이 만료된 사용자는 좌석을 선점할 수 없어야 한다.
- event id, user id, seat id가 누락되거나 빈 값이면 명확한 실패 응답을 받아야 한다.
- 같은 사용자가 다른 좌석으로 재시도해도 한 이벤트에서 두 개 이상의 좌석을 선점하면 안 된다.
- 이미 선점된 좌석에 대한 요청은 기존 소유자를 덮어쓰면 안 된다.
- 좌석 id가 허용된 좌석 범위를 벗어나면 실패해야 한다.
- Redis Lua Script 실행 중 일부 조건이 실패하면 좌석/사용자 예약 상태가 부분적으로 기록되면 안 된다.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 시스템은 event id, user id, seat id로 좌석 예매 요청을 받을 수 있어야 한다.
- **FR-002**: 시스템은 active admission이 있는 사용자만 좌석 선점을 성공시켜야 한다.
- **FR-003**: 시스템은 active admission이 없거나 만료된 사용자의 예매 요청을 NOT_ACTIVE로 거부해야 한다.
- **FR-004**: 시스템은 비어 있는 좌석을 요청한 active 사용자에게 RESERVED 결과를 반환해야 한다.
- **FR-005**: 시스템은 같은 event/seat 조합에 대해 둘 이상의 사용자가 RESERVED가 되지 않도록 해야 한다.
- **FR-006**: 시스템은 같은 event/user 조합에 대해 둘 이상의 좌석이 RESERVED가 되지 않도록 해야 한다.
- **FR-007**: 시스템은 좌석 선점 판단과 기록을 원자적으로 처리해야 한다.
- **FR-008**: 시스템은 사용자가 자신의 event/user 예약 결과를 조회할 수 있어야 한다.
- **FR-009**: 시스템은 아직 예약이 없는 사용자에게 NOT_RESERVED 결과를 반환해야 한다.
- **FR-010**: 시스템은 좌석 선점 성공 결과를 후속 비동기 영속화 기능이 읽을 수 있는 Redis reservation result로 남겨야 한다.
- **FR-011**: 시스템은 좌석 예매 hot path에서 MySQL을 직접 사용하지 않아야 한다.
- **FR-012**: 시스템은 잘못된 event id, user id, seat id 입력에 대해 명확한 실패 응답을 반환해야 한다.

### Key Entities *(include if feature involves data)*

- **Reservation Event**: 좌석 목록과 예약 상태가 격리되는 예매 이벤트.
- **Seat**: 하나의 예매 이벤트 안에서 선점 가능한 좌석.
- **Reservation Claim**: 한 event/user가 한 seat를 선점한 결과.
- **Active Admission**: 좌석 선점 요청 자격을 나타내는 기존 queue admission 권한.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: active admission이 있는 사용자는 비어 있는 좌석을 요청했을 때 RESERVED 결과를 받을 수 있다.
- **SC-002**: active admission이 없는 사용자의 좌석 예매 성공 건수는 0건이다.
- **SC-003**: 같은 좌석에 대한 동시 요청에서 RESERVED 결과는 최대 1건이다.
- **SC-004**: 같은 event/user 조합의 RESERVED 좌석은 최대 1개다.
- **SC-005**: 좌석 예매 요청과 결과 조회는 MySQL 없이 동작한다.
- **SC-006**: 좌석 선점 성공 후 사용자별 예약 결과 조회는 RESERVED 상태와 seat id를 반환한다.

## Assumptions

- 이번 기능은 프론트엔드 없이 HTTP API와 자동화 테스트로 검증한다.
- 좌석 선택은 클라이언트가 seat id를 지정하는 방식으로 시작한다. 무작위 좌석 선택은 후속 시뮬레이션 또는 부하 테스트 기능에서 다룬다.
- 기본 로컬 이벤트의 좌석 수는 2,000석으로 간주한다.
- 좌석 id는 `seat-1`부터 `seat-2000` 형식을 기본으로 허용한다.
- 이번 기능은 MySQL 저장을 직접 수행하지 않는다. Redis에 남은 reservation result를 후속 비동기 영속화 기능이 소비한다.
- full Idempotency-Key 기반 재시도 처리는 후속 idempotency 기능에서 다룬다. 이번 기능은 event/user 단위 중복 예매 방지를 제공한다.
