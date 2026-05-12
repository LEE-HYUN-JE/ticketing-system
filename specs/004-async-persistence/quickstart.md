# Quickstart: 비동기 예매 영속화

**Branch**: `004-async-persistence` | **Date**: 2026-05-10

## 로컬 환경 실행

```bash
# MySQL + Redis 실행
docker compose up -d

# 앱 실행
./gradlew bootRun
```

## 시나리오 1: 좌석 예매 → DB 저장 확인

### 1. 대기열 등록 및 active token 획득

```http
POST /api/events/event-1/queue
Content-Type: application/json

{ "userId": "user-1" }
```

응답:
```json
{ "queueToken": "user-1", "status": "WAITING" }
```

### 2. 예매 (active token이 발급된 후)

```http
POST /api/events/event-1/reservations
Idempotency-Key: req-uuid-001
Content-Type: application/json

{
  "userId": "user-1",
  "seatId": "seat-1"
}
```

성공 응답:
```json
{
  "status": "RESERVED",
  "seatId": "seat-1"
}
```

### 3. DB에서 예매 내역 확인

```sql
SELECT id, event_id, user_id, seat_id, status, reserved_at, idempotency_key
FROM reservations
WHERE event_id = 'event-1' AND user_id = 'user-1';
```

기대 결과:
```
id           | event_id | user_id | seat_id | status   | reserved_at         | idempotency_key
-------------|----------|---------|---------|----------|---------------------|----------------
a3f4c2e1-... | event-1  | user-1  | seat-1  | RESERVED | 2026-05-10 06:00:00 | req-uuid-001
```

---

## 시나리오 2: 정합성 검증 쿼리

부하 테스트 완료 후:

```sql
-- 성공 예매 수 (2,000 이하여야 함)
SELECT event_id, COUNT(*) FROM reservations WHERE status = 'RESERVED' GROUP BY event_id;

-- 중복 좌석 (0행이어야 함)
SELECT event_id, seat_id, COUNT(*) FROM reservations
WHERE status = 'RESERVED' GROUP BY event_id, seat_id HAVING COUNT(*) > 1;

-- 중복 사용자 (0행이어야 함)
SELECT event_id, user_id, COUNT(*) FROM reservations
WHERE status = 'RESERVED' GROUP BY event_id, user_id HAVING COUNT(*) > 1;
```

---

## 시나리오 3: Worker lag 확인

Redis Stream에 쌓인 미처리 이벤트 수:

```bash
redis-cli XLEN reservation-events
redis-cli XPENDING reservation-events persistence-workers - + 10
```

---

## 시나리오 4: Worker 재시작 복구 (US3)

1. 앱 실행 중 예매 몇 건 발생
2. 앱 강제 종료 (`Ctrl+C`)
3. 앱 재시작 (`./gradlew bootRun`)
4. 워커가 미처리 이벤트를 재소비하는지 로그 확인
5. DB에 해당 예매 내역 저장 확인

---

## 주요 설정값 (application.yml)

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/ticketing
    username: root
    password: root
  jpa:
    hibernate:
      ddl-auto: validate

reservation:
  persistence:
    stream-key: reservation-events
    consumer-group: persistence-workers
    consumer-name: worker-1
    batch-size: 10
    pending-idle-ms: 30000
```
