# Research: Reservation Idempotency

## Decision: Redis hash에 idempotency result 저장

`idempotency:{eventId}:{userId}:{idempotencyKey}` key를 Redis hash로 저장한다. 필드는 `status`, `seatId`, `message`, `requestSeatId`, `createdAt`을 사용하고, 기본 TTL은 600초다.

**Rationale**: 좌석 예매 hot path에서 MySQL을 사용하지 않아야 하며, 반복 요청은 빠르게 최초 결과를 반환해야 한다. Hash는 응답 필드를 구조적으로 저장하기 쉽고 TTL로 만료 정책을 단순하게 유지할 수 있다.

**Alternatives considered**:

- JSON 문자열 value: 구현은 단순하지만 Lua에서 필드별 처리가 불편하다.
- MySQL idempotency table: 내구성은 높지만 이번 기능 범위와 hot path 제약에 맞지 않는다.
- 요청 body hash 저장: 같은 key에 다른 seat id가 와도 최초 결과를 반환해야 하므로, 충돌 오류보다 결과 재사용이 요구사항에 더 맞다.

## Decision: 기존 좌석 선점 Lua script 안에 idempotency 조회/저장을 통합

`claim_seat.lua`가 idempotency key를 먼저 조회하고, 없으면 active admission, 사용자 기존 예약, 좌석 점유 확인, 좌석 선점, 결과 저장까지 한 번에 처리한다.

**Rationale**: 분리된 Redis 명령으로 check-then-act를 구현하면 동시 요청에서 race condition이 생길 수 있다. Lua script 한 번으로 처리하면 같은 event/user/key 중복 요청과 같은 event/user 다른 key 요청이 모두 원자적으로 판정된다.

**Alternatives considered**:

- Java service에서 idempotency key를 먼저 GET 후 claim 호출: 같은 key 동시 요청에서 두 claim이 동시에 진행될 수 있다.
- Redis lock key 별도 사용: lock 해제/만료/실패 처리 복잡도가 증가한다. 현재 요구사항은 단일 Lua 원자 처리로 충분하다.

## Decision: 실패 결과도 처리 결과로 저장

`NOT_ACTIVE`, `SEAT_ALREADY_TAKEN`, `ALREADY_RESERVED`, `RESERVED`를 idempotency result로 저장한다. 같은 event/user/key 재시도는 최초 실패 결과도 그대로 반환한다.

**Rationale**: 요구사항은 최초 결과 재사용이며, 실패처럼 보이는 응답 이후 클라이언트가 같은 key로 재시도해도 의미가 바뀌면 안 된다. 특히 active admission이 나중에 생기더라도 같은 key의 최초 `NOT_ACTIVE` 결과는 유지되어야 한다.

**Alternatives considered**:

- 성공 결과만 저장: 실패 재시도에서 다른 결과가 나올 수 있어 FR-007, FR-008과 충돌한다.
- `NOT_ACTIVE`만 저장하지 않기: active 상태 전환 타이밍에 따라 같은 key 결과가 달라질 수 있다.

## Decision: header validation 실패는 seat claim 전에 400으로 거절

`Idempotency-Key` 누락, blank, 120자 초과는 controller/application boundary에서 실패시키고 Redis 좌석 선점을 호출하지 않는다.

**Rationale**: 유효하지 않은 변경 요청은 idempotency 처리 대상이 아니다. 좌석 선점 전 명확히 거절하면 FR-009와 SC-005를 가장 단순하게 만족한다.

**Alternatives considered**:

- validation 실패도 Redis에 저장: key가 없거나 blank인 경우 event/user/key 식별자가 성립하지 않아 저장 기준이 애매하다.

## Decision: TTL 만료 후 같은 key는 새 요청으로 취급 가능

idempotency result가 만료되면 같은 event/user/key 조합도 신규 요청처럼 처리된다. 다만 기존 사용자 예약 hash가 남아 있으면 `ALREADY_RESERVED` 또는 기존 seat 반환 규칙은 계속 적용된다.

**Rationale**: spec의 가정은 기본 TTL 10분이며, 만료 후 새 요청 취급을 허용한다. 사용자별 예약 상태와 idempotency result의 생명주기를 분리하면 재시도 캐시와 예약 정합성을 각각 관리할 수 있다.

**Alternatives considered**:

- idempotency result 무기한 보관: Redis 메모리 사용량이 불필요하게 증가한다.
- 예약 상태와 같은 TTL 사용: 재시도 캐시 요구와 예약 상태 요구가 다르므로 결합하지 않는다.
