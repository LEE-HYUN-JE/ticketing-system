# Research: 비동기 예매 영속화

**Branch**: `004-async-persistence` | **Date**: 2026-05-10

## 결정 1: 이벤트 대기열 구현체

**Decision**: Redis Stream

**Rationale**:
- 애플리케이션 재시작 후에도 미처리 이벤트를 보존한다 (내부 큐는 재시작 시 유실).
- Consumer Group을 통해 at-least-once 전달을 보장한다.
- `XPENDING` / `XAUTOCLAIM`으로 워커 장애 후 재처리 시나리오를 표준 Redis 명령으로 처리할 수 있다.
- `XLEN`으로 worker lag을 직접 관찰할 수 있어 부하 테스트 지표 수집에 유리하다.
- architecture.md에서 명시적으로 언급된 구현체이다.

**Alternatives considered**:
- **Spring ApplicationEvent (in-memory)**: 구현이 단순하나 재시작 시 이벤트 유실, 테스트 가능성 낮음.
- **Kafka / RabbitMQ**: 포트폴리오 관점에서 외부 브로커를 이른 시점에 도입하면 핵심인 Redis + MySQL 정합성 증명에 집중하기 어려움. constitution에서 "확장 방향으로 문서화 가능하지만 로컬 실험의 목적을 흐리면 안 된다"고 명시.
- **Redis List (LPUSH/BRPOP)**: at-least-once 보장이 없음. 워커가 pop 후 처리 전 죽으면 이벤트 유실.

**Stream key 설계**: 단일 스트림 `reservation-events` 사용. 이벤트 내부에 eventId를 포함하므로 eventId별 스트림 분리는 불필요하다. 단일 워커로 소비하기 쉽고 stream length 관찰이 단순해진다.

---

## 결정 2: Worker 구동 방식

**Decision**: `@Scheduled` 기반 polling (XREADGROUP with block timeout)

**Rationale**:
- Spring Data Redis의 `StreamListener` / `StreamMessageListenerContainer`는 편리하지만 내부적으로 별도 스레드를 관리하며, consumer group 생성 및 재처리(XAUTOCLAIM) 로직을 직접 제어하기 어렵다.
- `@Scheduled`로 주기적으로 `XREADGROUP`을 호출하면 consumer group 생성, pending 재처리, ACK 로직을 명시적으로 제어할 수 있어 테스트와 디버깅이 쉽다.
- XREADGROUP의 `BLOCK 1000`(1초 대기)을 사용하면 polling 주기 없이 이벤트 도착을 기다릴 수 있다.

**Alternatives considered**:
- **StreamMessageListenerContainer**: Spring 추상화 편리하지만 XAUTOCLAIM 등 세밀한 제어가 어렵다.
- **별도 스레드**: 스케줄러로 충분히 제어 가능하며, 단일 인스턴스 워커이므로 복잡한 스레드 관리 불필요.

**Consumer Group**: `persistence-workers`, Consumer: `worker-1`
재처리 대상: `XCLAIM` / `XAUTOCLAIM`으로 idle time 초과 메시지를 재소유 처리.

---

## 결정 3: MySQL 의존성 추가 방법

**Decision**: Spring Data JPA + MySQL Connector/J + Testcontainers MySQL

**Rationale**:
- 프로젝트가 이미 Spring Boot 3 / Gradle 기반이므로 `spring-boot-starter-data-jpa` 추가가 가장 자연스럽다.
- Testcontainers의 MySQL 모듈로 통합 테스트를 Docker 없이 실행 가능하다.
- docker-compose.yml에 MySQL 서비스를 추가하여 로컬 재현성(constitution 원칙 III)을 만족한다.

**Schema 전략**: DDL auto = validate (운영), create-drop (테스트). Flyway/Liquibase는 이 기능 범위에 포함하지 않는다.

---

## 결정 4: DB 중복 방지 전략

**Decision**: DB unique constraint + DUPLICATE KEY 무시

**Rationale**:
- `reservations` 테이블에 `(event_id, user_id)` unique constraint를 추가한다.
- 동일 사용자 예매가 중복 발행되는 경우 DB 레벨에서 차단된다.
- Lua Script가 이미 Redis 레벨에서 중복을 막고 있으므로, DB 중복은 비정상 경로(워커 재처리 중 동일 메시지 중복 소비)에서만 발생한다.
- JPA의 `DataIntegrityViolationException`을 워커에서 catch하여 로그 남기고 ACK 처리한다 (이미 저장된 것으로 판단).

---

## 결정 5: 이벤트 발행 위치

**Decision**: `SeatReservationService.claimSeat()`에서 RESERVED 결과 반환 전 Redis Stream에 발행

**Rationale**:
- 좌석 선점 Lua Script 성공 후 즉시 이벤트를 발행한다.
- 이벤트 발행 실패(Redis 오류)는 로그로 남기고 예매 성공 응답은 그대로 반환한다. Redis에 이미 선점이 기록되었으므로 사용자 관점에서는 성공이다.
- 이벤트 발행과 좌석 선점의 원자성은 이 기능 범위에서 보장하지 않는다 (Redis transaction 복잡도 > 이득).

**Alternatives considered**:
- **Lua Script 내 XADD**: Redis Lua에서 XADD까지 원자적으로 실행 가능하지만, Lua Script 복잡도가 크게 증가하고 Stream key가 KEYS에 포함되어야 한다.
- **AOP/Interceptor**: 구현 복잡도 대비 이점 없음.

---

## 결정 6: 패키지 구조

**Decision**: 기존 `reservation` 패키지 하위에 `persistence` 서브패키지 추가

```
reservation/
├── api/          (기존)
├── application/  (기존)
├── domain/       (기존)
├── infrastructure/ (기존 - Redis)
└── persistence/  (신규 - JPA + Worker)
    ├── ReservationEntity.java
    ├── ReservationJpaRepository.java
    ├── ReservationEventPublisher.java
    └── ReservationPersistenceWorker.java
```

**Rationale**: 기존 패키지 구조(도메인별 패키지)와 일관성을 유지하고, 나중에 Worker를 별도 서비스로 분리할 때 패키지 경계가 명확하다.
