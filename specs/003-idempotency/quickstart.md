# Quickstart: Reservation Idempotency

## 1. 로컬 인프라 실행

```bash
docker compose up -d
```

## 2. 애플리케이션 실행

```bash
./gradlew bootRun
```

## 3. active admission 준비

기존 queue admission 흐름으로 사용자를 active 상태로 전환한다. 이미 테스트 코드에서는 Redis active admission key를 직접 준비하는 방식으로 검증할 수 있다.

## 4. 같은 key 재시도 확인

```bash
curl -sS -X POST 'http://localhost:8080/api/events/event-1/reservations' \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: reserve-user-1-seat-10' \
  -d '{"userId":"user-1","seatId":"seat-10"}'
```

같은 요청을 다시 보내면 `status`, `seatId`, `message`가 동일해야 한다.

```bash
curl -sS -X POST 'http://localhost:8080/api/events/event-1/reservations' \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: reserve-user-1-seat-10' \
  -d '{"userId":"user-1","seatId":"seat-10"}'
```

## 5. 같은 key, 다른 seat id 확인

```bash
curl -sS -X POST 'http://localhost:8080/api/events/event-1/reservations' \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: reserve-user-1-seat-10' \
  -d '{"userId":"user-1","seatId":"seat-11"}'
```

응답은 새 seat-11 결과가 아니라 최초 key의 결과여야 한다.

## 6. 다른 key, 같은 user 확인

```bash
curl -sS -X POST 'http://localhost:8080/api/events/event-1/reservations' \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: reserve-user-1-seat-11' \
  -d '{"userId":"user-1","seatId":"seat-11"}'
```

이미 같은 event/user가 좌석을 가진 경우 `ALREADY_RESERVED`와 기존 seat id를 반환해야 한다. 같은 key로 반복하면 이 `ALREADY_RESERVED` 결과가 재사용되어야 한다.

## 7. Idempotency-Key 검증 확인

```bash
curl -sS -X POST 'http://localhost:8080/api/events/event-1/reservations' \
  -H 'Content-Type: application/json' \
  -d '{"userId":"user-1","seatId":"seat-10"}'
```

`Idempotency-Key`가 없으면 `INVALID_REQUEST` code를 가진 400 응답이어야 하며 좌석 선점은 수행되지 않아야 한다. 120자를 초과하면 `BAD_REQUEST` code를 가진 400 응답이어야 한다.

## 8. 자동화 테스트

```bash
./gradlew test
```
