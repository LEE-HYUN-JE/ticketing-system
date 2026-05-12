# Reservation 컴포넌트

## 역할

`reservation` 컴포넌트는 active admission을 받은 사용자만 좌석을 선점하도록 보장하고, 성공한 예매 이벤트를 비동기로 MySQL에 저장합니다. 좌석 선점은 Redis Lua script로 원자 처리하며, `Idempotency-Key`를 사용해 중복 요청이 최초 결과를 재사용하도록 합니다.

핵심 책임:

- active admission이 있는 사용자만 좌석 선점 허용
- 좌석 ID 정책 검증
- 동일 좌석 중복 선점 방지
- 동일 사용자 중복 예매 방지
- `Idempotency-Key` 기반 중복 요청 결과 재생
- 성공한 예매 이벤트 Redis Stream 발행
- Redis Stream 이벤트를 MySQL로 비동기 저장
- 예매 조회 API 제공

## 주요 흐름

```text
POST /api/events/{eventId}/reservations
  -> ReservationController
  -> SeatReservationService
  -> SeatIdValidator
  -> RedisReservationRepository
  -> claim_seat.lua
  -> RESERVED면 ReservationEventPublisher
  -> Redis Stream reservation-events

ReservationPersistenceWorker 매 100ms
  -> Redis Stream XREADGROUP
  -> ReservationEntity 저장
  -> XACK

GET /api/events/{eventId}/reservations/users/{userId}
  -> ReservationController
  -> ReservationLookupService
  -> RedisReservationRepository
  -> 사용자 예매 상태 반환
```

## 폴더 구조

```text
src/main/java/com/example/ticketing/reservation
├── api
│   ├── ReservationController.java // 예매/조회 HTTP API 진입점
│   └── dto
│       ├── ReservationRequest.java  // 예매 요청 DTO
│       └── ReservationResponse.java // 예매 응답 DTO
├── application
│   ├── ReservationLookupService.java // 사용자 예매 조회 유스케이스
│   ├── ReservationProperties.java    // reservation.* 설정 바인딩
│   ├── SeatIdValidator.java          // 좌석 ID 정책 검증
│   └── SeatReservationService.java   // 좌석 선점 유스케이스
├── domain
│   ├── ReservationClaim.java         // 좌석 선점 요청 도메인 값
│   ├── ReservationEvent.java         // Redis Stream에 발행되는 예매 이벤트
│   ├── ReservationLookupResult.java  // 사용자 예매 조회 결과
│   ├── SeatClaimResult.java          // Lua script 좌석 선점 결과
│   └── ReservationStatus.java        // 예매 결과 상태 enum
├── infrastructure
│   ├── RedisReservationRepository.java // Lua 기반 좌석 선점 Redis 접근
│   └── ReservationRedisKeys.java       // Redis key 네이밍 규칙
└── persistence
    ├── ReservationEntity.java          // MySQL reservations 테이블 엔티티
    ├── ReservationEventPublisher.java  // 성공 예매 이벤트 Redis Stream 발행
    ├── ReservationJpaRepository.java   // MySQL 저장소
    ├── ReservationPersistenceProperties.java // stream/worker 설정 바인딩
    └── ReservationPersistenceWorker.java // Redis Stream -> MySQL 비동기 worker
```

## 클래스별 책임

### api

| 클래스 | 책임 |
|--------|------|
| `ReservationController` | `/api/events/{eventId}/reservations` 하위 API를 노출합니다. `POST`는 좌석 선점, `GET /users/{userId}`는 사용자 예매 조회입니다. |
| `dto.ReservationRequest` | 좌석 선점 요청 body입니다. `userId`, `seatId`를 받습니다. |
| `dto.ReservationResponse` | 예매 결과 응답입니다. `status`, `seatId`, `message`를 담습니다. |

### application

| 클래스 | 책임 |
|--------|------|
| `SeatReservationService` | 좌석 선점 유스케이스를 조율합니다. 필수값, idempotency key 길이, 좌석 ID를 검증한 뒤 Redis Lua script를 호출하고, 성공 시 비동기 저장 이벤트를 발행합니다. |
| `ReservationLookupService` | event/user 기준으로 Redis에 저장된 예매 결과를 조회합니다. 없으면 `NOT_RESERVED`를 반환합니다. |
| `SeatIdValidator` | `seat-{number}` 형식과 좌석 번호 범위를 검증합니다. |
| `ReservationProperties` | 좌석 수, 좌석 prefix, idempotency TTL, idempotency key 최대 길이, persistence 설정을 바인딩합니다. |

### domain

| 클래스 | 책임 |
|--------|------|
| `SeatClaimResult` | Redis Lua script가 반환한 좌석 선점 결과를 표현합니다. |
| `ReservationLookupResult` | 사용자 예매 조회 결과를 표현합니다. |
| `ReservationEvent` | MySQL 비동기 저장을 위해 Redis Stream에 발행할 이벤트입니다. |
| `ReservationClaim` | 좌석 선점 요청 자체를 표현하는 도메인 값입니다. 현재 유스케이스의 입력을 명확히 표현하기 위해 독립 record로 둡니다. |
| `ReservationStatus` | 예매 결과 상태 enum입니다. `RESERVED`, `ALREADY_RESERVED`, `SEAT_ALREADY_TAKEN`, `NOT_ACTIVE`, `INVALID_SEAT`, `NOT_RESERVED` 등을 표현합니다. |

### infrastructure

| 클래스 | 책임 |
|--------|------|
| `ReservationRedisKeys` | 좌석, 사용자 예매, idempotency key 등 reservation Redis key를 생성합니다. |
| `RedisReservationRepository` | `claim_seat.lua`를 실행해 active 검증, 중복 예매 검증, 좌석 선점, idempotency 결과 저장을 원자 처리합니다. 사용자 예매 조회도 담당합니다. |

### persistence

| 클래스 | 책임 |
|--------|------|
| `ReservationEntity` | MySQL `reservations` 테이블에 저장되는 예매 엔티티입니다. |
| `ReservationJpaRepository` | `ReservationEntity` 저장을 담당하는 Spring Data JPA repository입니다. |
| `ReservationEventPublisher` | `RESERVED` 성공 이벤트를 Redis Stream에 발행합니다. 발행 실패 시 API 성공 자체를 되돌리지는 않고 경고 로그를 남깁니다. |
| `ReservationPersistenceWorker` | Redis Stream consumer group으로 예매 이벤트를 읽어 MySQL에 저장합니다. pending 메시지 reclaim, 중복 저장 skip, XACK 처리를 포함합니다. |
| `ReservationPersistenceProperties` | Redis Stream key, consumer group, consumer name, batch size, pending idle, worker enable 설정을 바인딩합니다. |

## Redis 자료구조

```text
seat:{eventId}:{seatId}                         // 좌석 -> userId
reservation:user:{eventId}:{userId}             // 사용자 -> seatId/status/reservedAt hash
idempotency:{eventId}:{userId}:{idempotencyKey} // 중복 요청 결과 replay hash
reservation-events                              // MySQL 비동기 저장용 Redis Stream
```

## Lua script

| 파일 | 역할 |
|------|------|
| `claim_seat.lua` | active admission 검증, idempotency replay, 사용자 중복 예매 방지, 좌석 중복 선점 방지를 하나의 Redis script로 원자 처리합니다. |

## MySQL 저장

`schema.sql`의 `reservations` 테이블은 다음 제약으로 정합성을 보강합니다.

```text
PRIMARY KEY (id)
UNIQUE KEY uk_reservation_event_user (event_id, user_id)
UNIQUE KEY uk_reservation_event_seat (event_id, seat_id)
INDEX idx_reservation_event_id (event_id)
```

Redis에서 이미 원자 선점을 수행하지만, MySQL에도 event/user와 event/seat unique constraint를 두어 비동기 저장 중복을 방지합니다.

## 테스트 관점

reservation 컴포넌트는 다음을 중심으로 검증합니다.

- active admission 없는 사용자는 `NOT_ACTIVE`
- 유효하지 않은 좌석 ID는 `INVALID_SEAT`
- 같은 좌석은 한 사용자만 선점
- 같은 사용자는 한 좌석만 선점
- 같은 idempotency key 반복 요청은 최초 결과 반환
- 서로 다른 idempotency key라도 같은 event/user는 이미 예약된 좌석 하나만 유지
- 성공 예매 이벤트가 Redis Stream에 발행되고 MySQL에 저장
- worker가 pending 메시지를 다시 처리하고 중복 저장을 건너뜀
