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

State transitions:

```text
NOT_QUEUED -> WAITING -> ENTERED -> EXPIRED
```

Notes:

- `WAITING`은 사용자가 `waiting:{eventId}`에 존재함을 의미한다.
- `ENTERED`는 `active:{eventId}:{userId}`가 존재함을 의미한다.
- `EXPIRED`는 queue token이 unknown이거나, token의 event/user에 대해 waiting/active 상태가 더 이상 없음을 의미한다.
