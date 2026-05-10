# Data Model: Queue Admission

## ReservationEvent

기차표 예매 이벤트를 나타낸다.

Fields:

- `eventId`: 이벤트 식별자
- `admissionRatePerSecond`: scheduler interval마다 입장시킬 사용자 수
- `pollAfterSeconds`: waiting 사용자에게 반환할 권장 polling interval
- `activeTtlSeconds`: active admission 유지 시간

Validation rules:

- `eventId`는 필수다.
- `admissionRatePerSecond` 기본값은 300이다.
- `pollAfterSeconds` 기본값은 5다.
- `activeTtlSeconds` 기본값은 60이다.

## QueueEntry

한 예매 이벤트 안에서 사용자의 대기 위치를 나타낸다.

Fields:

- `eventId`: 이벤트 식별자
- `userId`: 사용자 식별자
- `requestedAt`: 진입 요청이 수락된 시각
- `queueToken`: 클라이언트에 반환되는 token

Validation rules:

- `eventId`와 `userId` 조합당 하나의 active waiting position만 존재할 수 있다.
- 정렬은 `requestedAt` 기준 oldest-first다.
- 동일 timestamp가 발생하면 구현 단계에서 deterministic secondary score component를 추가할 수 있다.

Redis representation:

```text
waiting:{eventId}
type: ZSET
member: userId
score: request timestamp

queue-events
type: SET
member: eventId
purpose: scheduler가 Redis KEYS 없이 입장 처리 대상 event id를 찾기 위한 registry
```

## QueueToken

클라이언트가 보유한 token을 event와 user에 매핑한다.

Fields:

- `token`: UUID string
- `eventId`: 이벤트 식별자
- `userId`: 사용자 식별자
- `createdAt`: token 생성 시각

Redis representation:

```text
queue-token:{token}
type: HASH
fields: eventId, userId, createdAt
ttl: configurable; 일반적인 load test 대기 시간보다 길어야 함

queue-user-token:{eventId}:{userId}
type: STRING
value: token
ttl: queue-token:{token}과 동일
purpose: 중복 진입 요청에서 Redis KEYS 없이 기존 token을 조회하기 위한 reverse index
```

## ActiveAdmission

사용자가 예매 단계로 진행할 수 있는 임시 권한을 나타낸다.

Fields:

- `eventId`: 이벤트 식별자
- `userId`: 사용자 식별자
- `enteredAt`: 입장 시각
- `expiresAt`: 만료 시각

Redis representation:

```text
active:{eventId}:{userId}
type: STRING
value: enteredAt
ttl: 기본 60초
```

## QueueMetrics

테스트와 진단에서 queue 동작 결과를 집계하기 위한 관찰 값이다.

Fields:

- `registeredCount`: queue entry 요청이 수락된 횟수
- `admittedCount`: scheduler가 active admission으로 전환한 횟수
- `currentWaitingCount`: 현재 waiting queue에 남아 있는 사용자 수
- `currentActiveCount`: 현재 TTL이 살아 있는 active admission 수
- `expiredLookupCount`: 상태 조회 또는 active admission 검증 중 EXPIRED로 판정된 횟수

Notes:

- Redis TTL로 삭제된 active key의 전체 개수를 정확히 세지 않는다.
- `expiredLookupCount`는 사용자가 token polling 또는 admission 검증을 시도했을 때 terminal non-active 상태로 판정된 횟수다.

State transitions:

```text
NOT_QUEUED -> WAITING -> ENTERED -> EXPIRED
```

Notes:

- `WAITING`은 사용자가 `waiting:{eventId}`에 존재함을 의미한다.
- `ENTERED`는 `active:{eventId}:{userId}`가 존재함을 의미한다.
- `EXPIRED`는 queue token이 unknown이거나, token의 event/user에 대해 waiting/active 상태가 더 이상 없음을 의미한다.
