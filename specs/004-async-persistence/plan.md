# Implementation Plan: 비동기 예매 영속화

**Branch**: `004-async-persistence` | **Date**: 2026-05-10 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `specs/004-async-persistence/spec.md`

## Summary

좌석 선점 성공 시 Redis Stream에 이벤트를 발행하고, Persistence Worker가 Consumer Group으로 이벤트를 소비하여 MySQL에 예매 내역을 저장한다. 예매 API 응답 속도는 DB 저장 완료를 기다리지 않는다.

## Technical Context

**Language/Version**: Java 21
**Primary Dependencies**: Spring Boot 3.4.5, Spring Data JPA, Spring Data Redis, MySQL Connector/J
**Storage**: Redis Stream (이벤트 대기열), MySQL (영속 저장)
**Testing**: JUnit 5, Testcontainers (Redis 기존, MySQL 신규)
**Target Platform**: 로컬 macOS, Docker Compose
**Project Type**: Spring Boot 단일 서비스 (기존 구조 유지)
**Performance Goals**: 예매 API p95 latency 영향 없음. Worker lag은 부하 테스트에서 관찰.
**Constraints**: MySQL은 대기열/예매 hot path에 없어야 함 (constitution 원칙 I)
**Scale/Scope**: 로컬 실험: 30,000 VU, 2,000석, admission rate 300/s

## Constitution Check

| 원칙 | 상태 | 근거 |
|------|------|------|
| I. 트래픽 제어 우선 | ✅ PASS | MySQL은 hot path(대기열/예매 API)에 없음. 비동기 저장만 담당 |
| II. 처리량보다 정합성 우선 | ✅ PASS | DB unique constraint + 워커 at-least-once 보장 |
| III. 로컬 재현성 | ✅ PASS | docker-compose.yml에 MySQL 추가, Testcontainers로 테스트 |
| IV. 테스트 기반 증거 | ✅ PASS | 단위/통합/API 테스트 포함 (tasks.md에서 정의) |
| V. 문서도 아키텍처의 일부 | ✅ PASS | research.md, data-model.md, quickstart.md 생성 |

## Project Structure

### Documentation (this feature)

```text
specs/004-async-persistence/
├── plan.md              # This file
├── research.md          # 기술 결정 및 근거
├── data-model.md        # MySQL 스키마, Redis Stream 스키마
├── quickstart.md        # 실행/검증 예시
└── tasks.md             # /speckit-tasks 출력 (미생성)
```

### Source Code

```text
src/main/java/com/example/ticketing/
├── common/
│   └── config/
│       ├── RedisConfig.java                    (기존)
│       └── PersistenceWorkerConfig.java        (신규: Worker 스케줄 설정)
└── reservation/
    ├── api/                                    (기존)
    ├── application/
    │   └── SeatReservationService.java         (수정: 이벤트 발행 추가)
    ├── domain/
    │   └── ReservationModels.java              (수정: ReservationEvent record 추가)
    ├── infrastructure/                         (기존: Redis)
    └── persistence/                            (신규)
        ├── ReservationEntity.java
        ├── ReservationJpaRepository.java
        ├── ReservationPersistenceProperties.java
        ├── ReservationEventPublisher.java
        └── ReservationPersistenceWorker.java

src/main/resources/
├── application.yml                             (수정: datasource, jpa, persistence 설정)
├── application-test.yml                        (수정: Testcontainers datasource)
└── schema.sql                                  (신규: DDL)

docker-compose.yml                              (수정: MySQL 서비스 추가)
build.gradle                                    (수정: JPA, MySQL, Testcontainers MySQL 추가)
```

### Test Code

```text
src/test/java/com/example/ticketing/
├── support/
│   ├── RedisIntegrationTestSupport.java        (기존)
│   └── MysqlIntegrationTestSupport.java        (신규: Testcontainers MySQL)
└── reservation/
    ├── persistence/
    │   ├── ReservationEventPublisherTest.java
    │   └── ReservationPersistenceWorkerTest.java
    └── integration/
        └── ReservationPersistenceApiTest.java
```

## 의존성 변경 (build.gradle)

```groovy
// JPA + MySQL
implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
runtimeOnly 'com.mysql:mysql-connector-j'

// Testcontainers MySQL
testImplementation 'org.testcontainers:mysql'
```

## 설정 변경

### application.yml 추가 항목

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/ticketing
    username: root
    password: root
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false

reservation:
  persistence:
    stream-key: reservation-events
    consumer-group: persistence-workers
    consumer-name: worker-1
    batch-size: 10
    pending-idle-ms: 30000
```

### application-test.yml 추가 항목

```yaml
spring:
  datasource:
    url: jdbc:tc:mysql:8.0:///ticketing
    driver-class-name: org.testcontainers.jdbc.ContainerDatabaseDriver
  jpa:
    hibernate:
      ddl-auto: create-drop
```

### docker-compose.yml MySQL 추가

```yaml
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: ticketing
    ports:
      - "3306:3306"
    command: --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
```

## 핵심 컴포넌트 설계

### ReservationEventPublisher

`SeatReservationService`에서 RESERVED 결과 직후 호출.

- `XADD reservation-events * reservationId {uuid} eventId {..} userId {..} seatId {..} status RESERVED reservedAt {..} idempotencyKey {..}`
- 발행 실패 시 warn 로그만 남기고 예매 응답은 그대로 반환

### ReservationPersistenceWorker

`@Scheduled(fixedDelay = 100)` + `@Component`로 앱 기동 후 즉시 루프 시작.

```
기동 시: XGROUP CREATE reservation-events persistence-workers $ MKSTREAM (이미 있으면 무시)

루프:
  1. XAUTOCLAIM persistence-workers worker-1 30000 0-0 COUNT 10
     → pending idle > 30초 메시지 재소유 처리
  2. XREADGROUP GROUP persistence-workers worker-1 BLOCK 1000 COUNT 10 STREAMS reservation-events >
  3. 각 메시지 → ReservationEntity 변환 후 JPA save
  4. DataIntegrityViolationException → 이미 저장된 것으로 간주, warn 로그
  5. XACK reservation-events persistence-workers {messageId}
```

### ReservationEntity (JPA)

```java
@Entity
@Table(name = "reservations",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_reservation_event_user",
            columnNames = {"event_id", "user_id"}),
        @UniqueConstraint(name = "uk_reservation_event_seat",
            columnNames = {"event_id", "seat_id"})
    })
public class ReservationEntity {
    @Id private String id;           // UUID
    @Column(name = "event_id")   private String eventId;
    @Column(name = "user_id")    private String userId;
    @Column(name = "seat_id")    private String seatId;
    private String status;
    @Column(name = "reserved_at") private Instant reservedAt;
    @Column(name = "idempotency_key") private String idempotencyKey;
    @Column(name = "created_at") private Instant createdAt;
}
```

### schema.sql (테스트용)

```sql
CREATE TABLE IF NOT EXISTS reservations (
    id              VARCHAR(36)  NOT NULL,
    event_id        VARCHAR(255) NOT NULL,
    user_id         VARCHAR(255) NOT NULL,
    seat_id         VARCHAR(255) NOT NULL,
    status          VARCHAR(50)  NOT NULL,
    reserved_at     DATETIME(6)  NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_reservation_event_user (event_id, user_id),
    UNIQUE KEY uk_reservation_event_seat (event_id, seat_id),
    INDEX idx_reservation_event_id (event_id)
);
```

## 구현 제약

- MySQL 연결은 대기열/예매 hot path에 없어야 한다. `SeatReservationService.claimSeat()`는 Redis 호출만 한다.
- 이벤트 발행 실패 시 예매 응답은 성공으로 반환한다 (Redis에 선점 기록 완료).
- 워커는 단일 인스턴스로 시작한다.
- `ddl-auto: validate` 운영 설정을 위해 `schema.sql`은 테스트 리소스에 위치한다.
- 운영 DDL은 `src/main/resources/schema.sql`에 별도 관리한다.
