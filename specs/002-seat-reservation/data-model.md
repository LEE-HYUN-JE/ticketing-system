# Data Model: Seat Reservation

## ReservationEvent

좌석 예매가 격리되는 이벤트다.

Fields:

- `eventId`: 이벤트 식별자
- `seatCapacity`: 좌석 수

Validation rules:

- `eventId`는 필수다.
- 기본 로컬 이벤트의 `seatCapacity`는 2,000이다.

## Seat

하나의 예매 이벤트 안에서 선점 가능한 좌석이다.

Fields:

- `eventId`: 이벤트 식별자
- `seatId`: 좌석 식별자
- `reservedBy`: 좌석을 선점한 user id

Validation rules:

- `seatId`는 `seat-1`부터 `seat-2000` 형식이어야 한다.
- 하나의 `eventId`와 `seatId` 조합은 최대 한 명의 사용자만 소유할 수 있다.

Redis representation:

```text
seat:{eventId}:{seatId}
type: STRING
value: userId
```

## ReservationClaim

한 사용자가 한 이벤트에서 좌석을 선점한 결과다.

Fields:

- `eventId`: 이벤트 식별자
- `userId`: 사용자 식별자
- `seatId`: 선점된 좌석 식별자
- `status`: RESERVED
- `reservedAt`: 선점 시각

Validation rules:

- 하나의 `eventId`와 `userId` 조합은 최대 하나의 ReservationClaim만 가질 수 있다.
- 좌석 선점은 active admission이 있을 때만 가능하다.

Redis representation:

```text
reservation:user:{eventId}:{userId}
type: HASH
fields: seatId, status, reservedAt
```

## ActiveAdmission

queue admission feature에서 생성한 예매 진입 권한이다.

Redis representation:

```text
active:{eventId}:{userId}
type: STRING
value: enteredAt
ttl: queue feature 설정에 따름
```

## ReservationResult

좌석 예매 요청 또는 조회의 결과 상태다.

States:

- `RESERVED`: 좌석 선점 성공 또는 기존 예약 확인
- `NOT_ACTIVE`: active admission이 없어 좌석 선점 거부
- `SEAT_ALREADY_TAKEN`: 선택 좌석이 이미 다른 사용자에게 선점됨
- `ALREADY_RESERVED`: 같은 사용자가 이미 이 이벤트에서 좌석을 선점함
- `NOT_RESERVED`: 조회 시 아직 예약 결과가 없음
- `INVALID_SEAT`: 좌석 id가 허용 범위를 벗어남

State transitions:

```text
NOT_RESERVED -> RESERVED
NOT_RESERVED -> NOT_ACTIVE
NOT_RESERVED -> SEAT_ALREADY_TAKEN
RESERVED -> ALREADY_RESERVED
```

