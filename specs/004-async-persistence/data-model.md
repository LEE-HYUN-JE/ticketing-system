# Data Model: 비동기 예매 영속화

**Branch**: `004-async-persistence` | **Date**: 2026-05-10

## MySQL: reservations 테이블

### 스키마

| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| `id` | VARCHAR(36) | PK | UUID 예매 ID (시스템 생성) |
| `event_id` | VARCHAR(255) | NOT NULL | 예매 이벤트 ID |
| `user_id` | VARCHAR(255) | NOT NULL | 사용자 ID |
| `seat_id` | VARCHAR(255) | NOT NULL | 좌석 ID (예: `seat-1`) |
| `status` | VARCHAR(50) | NOT NULL | 예매 상태 (`RESERVED`) |
| `reserved_at` | DATETIME(6) | NOT NULL | 좌석 선점 시각 (UTC) |
| `idempotency_key` | VARCHAR(120) | NOT NULL | 요청 멱등성 키 |
| `created_at` | DATETIME(6) | NOT NULL, DEFAULT NOW | DB 저장 시각 |

### Unique Constraints

| 이름 | 컬럼 | 목적 |
|------|------|------|
| `uk_reservation_event_user` | `(event_id, user_id)` | 동일 이벤트에서 사용자당 1예매 |
| `uk_reservation_event_seat` | `(event_id, seat_id)` | 동일 이벤트에서 좌석당 1예매 |

### Index

| 이름 | 컬럼 | 목적 |
|------|------|------|
| `idx_reservation_event_id` | `event_id` | 이벤트별 예매 목록 조회 |

### DDL (참고용)

```sql
CREATE TABLE reservations (
    id           VARCHAR(36)  NOT NULL,
    event_id     VARCHAR(255) NOT NULL,
    user_id      VARCHAR(255) NOT NULL,
    seat_id      VARCHAR(255) NOT NULL,
    status       VARCHAR(50)  NOT NULL,
    reserved_at  DATETIME(6)  NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    UNIQUE KEY uk_reservation_event_user (event_id, user_id),
    UNIQUE KEY uk_reservation_event_seat (event_id, seat_id),
    INDEX idx_reservation_event_id (event_id)
);
```

---

## Redis: reservation-events Stream

### Key

```
reservation-events
type: Stream
```

단일 전역 스트림. 이벤트 내부 `eventId` 필드로 이벤트 구분.

### 메시지 필드

| 필드 | 타입 | 예시 | 설명 |
|------|------|------|------|
| `reservationId` | String | `a3f4c2...` | UUID (발행 시 생성) |
| `eventId` | String | `event-2026` | 예매 이벤트 ID |
| `userId` | String | `user-1` | 사용자 ID |
| `seatId` | String | `seat-42` | 좌석 ID |
| `status` | String | `RESERVED` | 예매 상태 |
| `reservedAt` | String | `2026-05-10T06:00:00Z` | ISO-8601 UTC |
| `idempotencyKey` | String | `req-uuid-123` | 요청 멱등성 키 |

### Consumer Group

| 항목 | 값 |
|------|-----|
| Group name | `persistence-workers` |
| Consumer name | `worker-1` |
| Start offset | `$` (최초 생성 시) |
| Reprocess | XAUTOCLAIM (idle > 30초) |
| Max messages per poll | 10 |
| Block timeout | 1,000ms |

### Stream Lifecycle

- 스트림은 워커 최초 기동 시 consumer group 생성과 함께 시작된다 (`XGROUP CREATE reservation-events persistence-workers $ MKSTREAM`).
- 처리 완료 메시지는 `XACK` 처리된다.
- 스트림 최대 길이는 별도 설정하지 않는다 (로컬 실험 규모에서는 불필요).

---

## Redis: 기존 키와의 관계

이 기능에서 새로 추가되는 Redis 키는 `reservation-events` 스트림뿐이다. 기존 키(seat, reservation:user, active, waiting, idempotency)는 수정하지 않는다.

---

## 정합성 검증 쿼리

부하 테스트 후 실행할 SQL:

```sql
-- 이벤트별 성공 예매 수 (2,000 초과 불가)
SELECT event_id, COUNT(*) as reserved_count
FROM reservations
WHERE status = 'RESERVED'
GROUP BY event_id;

-- 중복 좌석 확인 (결과 0행이어야 함)
SELECT event_id, seat_id, COUNT(*)
FROM reservations
WHERE status = 'RESERVED'
GROUP BY event_id, seat_id
HAVING COUNT(*) > 1;

-- 중복 사용자 확인 (결과 0행이어야 함)
SELECT event_id, user_id, COUNT(*)
FROM reservations
WHERE status = 'RESERVED'
GROUP BY event_id, user_id
HAVING COUNT(*) > 1;
```
