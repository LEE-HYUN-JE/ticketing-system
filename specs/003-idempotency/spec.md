# Feature Specification: Reservation Idempotency

**Feature Branch**: `003-idempotency`  
**Created**: 2026-05-10  
**Status**: Draft  
**Input**: User description: "Idempotency-Key 기반 중복 예약 요청 차단 기능을 만든다. 같은 event/user/idempotency key로 반복된 좌석 예매 요청은 좌석을 다시 선점하지 않고 최초 결과를 그대로 반환해야 한다. 서로 다른 key는 별도 요청으로 취급하지만, 같은 event/user는 이미 예약된 좌석 하나만 가질 수 있다. MySQL 영속화는 이번 기능에서 다루지 않는다."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 같은 요청 재시도 결과 재사용 (Priority: P1)

사용자는 네트워크 재시도나 클라이언트 중복 전송으로 같은 event, user, Idempotency-Key 조합의 좌석 예매 요청을 여러 번 보낼 수 있다. 시스템은 첫 번째 처리 결과를 저장하고, 이후 같은 조합의 요청에는 좌석 선점을 다시 시도하지 않고 최초 결과를 그대로 반환한다.

**Why this priority**: 예매 요청은 실패처럼 보인 뒤 재시도될 수 있다. 같은 요청이 반복될 때 새 좌석을 추가로 잡거나 서로 다른 결과를 반환하면 사용자 신뢰와 정합성이 깨진다.

**Independent Test**: active admission을 가진 사용자가 같은 Idempotency-Key로 같은 좌석 예매 요청을 두 번 보내면 두 응답이 동일하고, Redis 좌석/사용자 예약 결과는 한 번만 생성되는지 확인한다.

**Acceptance Scenarios**:

1. **Given** active admission을 가진 사용자와 비어 있는 좌석, **When** 사용자가 Idempotency-Key와 함께 좌석 예매를 요청하고 같은 요청을 다시 보내면, **Then** 두 번째 응답은 첫 번째 RESERVED 결과와 동일하다.
2. **Given** active admission이 없는 사용자, **When** 사용자가 Idempotency-Key와 함께 좌석 예매를 반복 요청하면, **Then** 두 번째 응답은 최초 NOT_ACTIVE 결과와 동일하다.
3. **Given** 이미 처리된 Idempotency-Key, **When** 같은 event/user/key로 다른 seat id를 보내면, **Then** 시스템은 새 좌석 선점을 시도하지 않고 최초 결과를 반환한다.

---

### User Story 2 - 서로 다른 key는 별도 요청으로 처리 (Priority: P2)

사용자가 서로 다른 Idempotency-Key로 좌석 예매를 요청하면 시스템은 각각을 별도 요청으로 취급한다. 단, 같은 event/user는 이미 하나의 좌석만 가질 수 있으므로 두 번째 key는 기존 사용자 예약 상태에 맞는 결과를 반환한다.

**Why this priority**: idempotency는 같은 요청의 재시도만 묶어야 한다. 서로 다른 의도의 요청까지 무조건 같은 결과로 묶으면 사용자가 실패 후 새 요청을 시작할 수 없다.

**Independent Test**: 같은 사용자가 첫 번째 key로 좌석을 RESERVED한 뒤 다른 key로 다른 좌석을 요청하면 ALREADY_RESERVED와 기존 seat id가 반환되는지 확인한다.

**Acceptance Scenarios**:

1. **Given** 사용자가 key A로 seat-10을 RESERVED한 상태, **When** 같은 사용자가 key B로 seat-11을 요청하면, **Then** 응답은 ALREADY_RESERVED와 seat-10을 반환한다.
2. **Given** key B 요청도 처리된 상태, **When** 같은 key B 요청을 다시 보내면, **Then** key B의 최초 ALREADY_RESERVED 결과가 그대로 반환된다.

---

### User Story 3 - Idempotency-Key 입력 검증 (Priority: P3)

좌석 예매 요청은 Idempotency-Key를 명시해야 하며, 누락되거나 빈 값이면 처리되지 않는다.

**Why this priority**: 변경 요청의 재시도 규칙이 명확해야 이후 클라이언트와 부하 테스트가 안전하게 재시도할 수 있다.

**Independent Test**: Idempotency-Key 없이 좌석 예매 요청을 보내면 좌석이 선점되지 않고 명확한 실패 응답이 반환되는지 확인한다.

**Acceptance Scenarios**:

1. **Given** active admission을 가진 사용자, **When** 사용자가 Idempotency-Key 없이 좌석 예매를 요청하면, **Then** 요청은 실패하고 좌석은 선점되지 않는다.
2. **Given** 빈 Idempotency-Key, **When** 사용자가 좌석 예매를 요청하면, **Then** 요청은 실패하고 좌석은 선점되지 않는다.

### Edge Cases

- 같은 Idempotency-Key로 seat id가 달라져도 최초 처리 결과를 반환해야 한다.
- 최초 결과가 실패 상태여도 같은 key 재시도는 같은 실패 상태를 반환해야 한다.
- 같은 key가 다른 user 또는 다른 event에서 사용되면 별도 요청으로 취급해야 한다.
- Idempotency-Key가 너무 길거나 빈 값이면 명확한 실패 응답을 받아야 한다.
- idempotency result가 만료된 뒤 같은 key가 다시 오면 새 요청으로 취급할 수 있다.
- 요청 처리 중 일부 조건이 실패해도 idempotency result는 일관된 상태로 남아야 한다.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 시스템은 좌석 예매 요청에 Idempotency-Key를 요구해야 한다.
- **FR-002**: 시스템은 event id, user id, Idempotency-Key 조합으로 요청 결과를 구분해야 한다.
- **FR-003**: 시스템은 같은 event/user/key 조합의 반복 요청에 대해 최초 처리 결과를 그대로 반환해야 한다.
- **FR-004**: 시스템은 같은 event/user/key 반복 요청에서 좌석 선점을 다시 시도하면 안 된다.
- **FR-005**: 시스템은 서로 다른 Idempotency-Key를 별도 요청으로 취급해야 한다.
- **FR-006**: 시스템은 서로 다른 key로 재요청하더라도 같은 event/user가 둘 이상의 좌석을 RESERVED하지 못하게 해야 한다.
- **FR-007**: 시스템은 active admission이 없는 요청의 NOT_ACTIVE 결과도 같은 key 재시도에서 재사용해야 한다.
- **FR-008**: 시스템은 이미 점유된 좌석에 대한 SEAT_ALREADY_TAKEN 결과도 같은 key 재시도에서 재사용해야 한다.
- **FR-009**: 시스템은 Idempotency-Key 누락, 빈 값, 허용 길이 초과에 대해 명확한 실패 응답을 반환해야 한다.
- **FR-010**: 시스템은 idempotency result에 만료 정책을 가져야 한다.
- **FR-011**: 시스템은 idempotency 처리를 포함한 좌석 예매 hot path에서 MySQL을 직접 사용하지 않아야 한다.

### Key Entities *(include if feature involves data)*

- **Idempotency Request**: event, user, key로 식별되는 하나의 변경 요청.
- **Idempotency Result**: 최초 요청 처리 결과와 seat id, message를 저장한 재사용 가능한 결과.
- **Reservation Claim**: 좌석 예매 기능에서 생성되는 사용자별 좌석 선점 결과.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 같은 event/user/key로 같은 좌석 예매 요청을 2회 이상 보내도 RESERVED 좌석은 최대 1개다.
- **SC-002**: 같은 event/user/key의 반복 요청은 status, seat id, message가 동일한 응답을 반환한다.
- **SC-003**: active admission이 없는 요청의 반복 재시도는 모두 동일한 NOT_ACTIVE 결과를 반환한다.
- **SC-004**: 서로 다른 key로 같은 사용자가 다른 좌석을 요청해도 같은 event/user의 RESERVED 좌석은 최대 1개다.
- **SC-005**: Idempotency-Key가 누락된 변경 요청은 좌석을 선점하지 않는다.
- **SC-006**: idempotency 처리는 MySQL 없이 동작한다.

## Assumptions

- Idempotency-Key는 HTTP header로 전달한다.
- Idempotency-Key의 최대 길이는 120자로 제한한다.
- idempotency result TTL 기본값은 10분이다.
- 이번 기능은 Redis에 idempotency result를 저장한다.
- full distributed in-progress locking은 단일 Redis Lua Script의 원자 처리로 해결한다.
- MySQL 영속화는 이번 기능에서 다루지 않는다.
