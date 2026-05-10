# Quickstart: Seat Reservation

이 quickstart는 구현 후 active admission을 가진 사용자가 좌석을 선점하는 흐름을 확인하는 방법을 설명합니다.

## 준비물

- Java 21
- Docker
- Docker Compose

## 인프라 실행

```bash
docker compose up -d redis
```

## 테스트 실행

```bash
./gradlew test
```

기대 테스트 범위:

- active admission이 있는 사용자는 비어 있는 좌석을 RESERVED로 선점한다.
- active admission이 없는 사용자는 NOT_ACTIVE가 된다.
- 같은 좌석에 대한 동시 요청은 하나만 RESERVED가 된다.
- 같은 event/user 조합은 둘 이상의 좌석을 선점하지 않는다.
- 예매 결과 조회는 NOT_RESERVED 또는 RESERVED를 반환한다.
- 좌석 예매와 결과 조회는 MySQL을 필요로 하지 않는다.

## 애플리케이션 실행

```bash
./gradlew bootRun
```

## 수동 API 흐름

먼저 queue admission 기능으로 active admission을 만든다.

```bash
curl -X POST http://localhost:8080/api/events/holiday-2026/queue \
  -H 'Content-Type: application/json' \
  -d '{"userId":"user-1"}'
```

scheduler가 사용자를 active 상태로 전환한 뒤 좌석 예매를 요청한다.

```bash
curl -X POST http://localhost:8080/api/events/holiday-2026/reservations \
  -H 'Content-Type: application/json' \
  -d '{"userId":"user-1","seatId":"seat-10"}'
```

기대 응답:

```json
{
  "status": "RESERVED",
  "seatId": "seat-10"
}
```

사용자 예매 결과 조회:

```bash
curl http://localhost:8080/api/events/holiday-2026/reservations/users/user-1
```

active admission 없이 요청하면:

```json
{
  "status": "NOT_ACTIVE"
}
```

같은 사용자가 이미 다른 좌석을 선점한 뒤 다시 요청하면:

```json
{
  "status": "ALREADY_RESERVED",
  "seatId": "seat-10"
}
```

다른 사용자가 이미 선점된 좌석을 요청하면:

```json
{
  "status": "SEAT_ALREADY_TAKEN",
  "seatId": "seat-10"
}
```

아직 좌석을 선점하지 않은 사용자의 결과 조회:

```json
{
  "status": "NOT_RESERVED"
}
```
