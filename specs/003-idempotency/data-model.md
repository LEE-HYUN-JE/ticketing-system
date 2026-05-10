# Data Model: Reservation Idempotency

## Entity: Idempotency Request

하나의 변경 요청을 식별하는 논리 엔티티다.

| Field | Type | Validation | Notes |
|-------|------|------------|-------|
| `eventId` | string | required, path variable | 이벤트별 namespace |
| `userId` | string | required, request body | 기존 예약 API body 유지 |
| `idempotencyKey` | string | required, non-blank, max 120 chars | HTTP `Idempotency-Key` header |
| `requestSeatId` | string | required, valid seat id | 최초 요청 seat id 기록용 |

## Entity: Idempotency Result

최초 처리 결과를 재사용하기 위해 Redis에 저장하는 결과 엔티티다.

| Field | Type | Validation | Notes |
|-------|------|------------|-------|
| `status` | enum | required | `RESERVED`, `ALREADY_RESERVED`, `SEAT_ALREADY_TAKEN`, `NOT_ACTIVE` |
| `seatId` | string | nullable | 실패 유형에 따라 empty 가능 |
| `message` | string | required | API 응답 message와 동일 |
| `requestSeatId` | string | required | 최초 요청이 시도한 seat id |
| `createdAt` | ISO-8601 instant | required | 최초 처리 시각 |
| `ttlSeconds` | number | default 600 | Redis key expiration |

### Redis Representation

```text
key: idempotency:{eventId}:{userId}:{idempotencyKey}
type: HASH
fields:
  status
  seatId
  message
  requestSeatId
  createdAt
ttl: reservation.idempotency-ttl-seconds (default 600)
```

### State Transitions

```text
ABSENT
  -> STORED_RESERVED
  -> STORED_ALREADY_RESERVED
  -> STORED_SEAT_ALREADY_TAKEN
  -> STORED_NOT_ACTIVE

STORED_* -> EXPIRED
EXPIRED -> ABSENT
```

동일 key 재시도는 `STORED_*` 상태를 변경하지 않고 저장된 필드를 그대로 반환한다.

## Entity: Reservation Claim

기존 좌석 선점 모델이다. 이번 기능에서는 idempotency result 저장과 같은 Lua script 안에서 함께 처리된다.

| Field | Type | Validation | Notes |
|-------|------|------------|-------|
| `eventId` | string | required | Redis key namespace |
| `userId` | string | required | 한 event에서 하나의 reserved seat만 허용 |
| `seatId` | string | required, configured range | `seat-1` 형식 |
| `reservedAt` | ISO-8601 instant | required on success | 사용자 예약 hash에 저장 |

### Existing Redis Keys

```text
active admission: active:{eventId}:{userId}
seat owner: reservation:{eventId}:seat:{seatId}
user reservation: reservation:{eventId}:user:{userId}
```

### Invariants

- 같은 `eventId + userId + idempotencyKey`는 TTL 안에서 하나의 `Idempotency Result`만 가진다.
- 같은 `eventId + userId`는 하나의 reserved `seatId`만 가진다.
- 같은 `eventId + seatId`는 하나의 owner `userId`만 가진다.
- idempotency replay는 seat owner나 user reservation을 다시 쓰지 않는다.
