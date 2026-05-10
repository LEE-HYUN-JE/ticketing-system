# Implementation Plan: Reservation Idempotency

**Branch**: `003-idempotency` | **Date**: 2026-05-10 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `specs/003-idempotency/spec.md`

## Summary

좌석 예매 POST 요청에 `Idempotency-Key` header를 필수로 요구하고, `eventId + userId + idempotencyKey` 조합의 최초 처리 결과를 Redis에 TTL과 함께 저장한다. 동일 조합 재시도는 좌석 선점을 다시 수행하지 않고 저장된 `status`, `seatId`, `message`를 그대로 반환한다. 서로 다른 key는 별도 요청으로 처리하되 기존 사용자별 예약 Redis 상태를 기준으로 같은 event/user가 둘 이상의 좌석을 예약하지 못하게 한다.

핵심 구현은 기존 `claim_seat.lua`에 idempotency result 조회/저장을 추가하는 방식이다. active admission 확인, 사용자 중복 예약 확인, 좌석 점유 확인, 결과 저장을 하나의 Lua script 안에서 처리해 MySQL 없이 원자성을 유지한다.

## Technical Context

**Language/Version**: Java 21  
**Primary Dependencies**: Spring Boot 3.x, Spring Web, Spring Data Redis, Lettuce, JUnit 5, AssertJ, Testcontainers Redis  
**Storage**: Redis hot path. MySQL 영속화는 이번 기능 범위에서 제외  
**Testing**: Gradle `test`, Spring MVC integration test, Redis integration test, application service unit test  
**Target Platform**: 단일 Spring Boot WAS, 로컬 Docker Compose Redis/MySQL 환경  
**Project Type**: backend web service  
**Performance Goals**: 중복 재시도는 Redis Lua script 1회 실행으로 처리하며, 재시도 시 추가 좌석 선점/사용자 예약 쓰기를 만들지 않는다.  
**Constraints**: `Idempotency-Key` 필수, 공백 불가, 최대 120자, 기본 TTL 10분, Redis `KEYS` 명령 금지, 좌석 선점 hot path에서 MySQL 사용 금지  
**Scale/Scope**: 기존 로컬 목표인 30,000 VU/2,000 좌석 시나리오를 훼손하지 않는 범위의 예약 API 멱등성. 이번 plan은 부하 테스트 스크립트 작성은 포함하지 않는다.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. 트래픽 제어 우선**: PASS. 기존 active admission key 확인을 유지하고, admission 없는 요청은 `NOT_ACTIVE` 결과로 저장/재사용한다.
- **II. 처리량보다 정합성 우선**: PASS. 같은 event/user/key 반복 요청은 최초 결과를 재사용하고, 다른 key라도 같은 event/user는 기존 사용자 예약 상태로 하나의 좌석만 허용한다.
- **III. 로컬 재현성**: PASS. Redis 기반 구현과 Gradle 테스트로 로컬 재현 가능하다. 새 수동 인프라는 없다.
- **IV. 테스트 기반 증거**: PASS. controller validation, service behavior, Redis Lua integration, same-key/different-key 시나리오 테스트가 필요하다.
- **V. 문서도 아키텍처의 일부**: PASS. Redis key/data model, API contract, quickstart를 이 feature 산출물에 기록한다.

## Project Structure

### Documentation (this feature)

```text
specs/003-idempotency/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── reservation-api.yaml
├── checklists/
│   └── requirements.md
└── tasks.md
```

### Source Code (repository root)

```text
src/main/java/com/example/ticketing/reservation/
├── api/
│   ├── ReservationController.java
│   └── ReservationDtos.java
├── application/
│   ├── ReservationProperties.java
│   ├── SeatIdValidator.java
│   └── SeatReservationService.java
├── domain/
│   ├── ReservationModels.java
│   └── ReservationStatus.java
└── infrastructure/
    ├── RedisReservationRepository.java
    └── ReservationRedisKeys.java

src/main/resources/
├── application.yml
└── lua/
    └── claim_seat.lua

src/test/java/com/example/ticketing/reservation/
├── application/
├── infrastructure/
└── integration/
```

**Structure Decision**: 기존 단일 Spring Boot 서비스와 reservation package 경계를 유지한다. 새 idempotency 동작은 별도 서비스로 과분하게 분리하지 않고, API header validation, application method signature, Redis repository/Lua script 확장으로 구현한다.

## Complexity Tracking

헌법 위반 없음. 추가 복잡성 추적 항목 없음.

## Phase 0 Research

[research.md](./research.md)에 결정 사항을 정리했다.

## Phase 1 Design

- [data-model.md](./data-model.md): Redis idempotency result, reservation claim 관계와 상태 전이
- [contracts/reservation-api.yaml](./contracts/reservation-api.yaml): `Idempotency-Key` header가 추가된 예약 API 계약
- [quickstart.md](./quickstart.md): 로컬 실행 및 curl 기반 멱등성 확인 절차

## Post-Design Constitution Check

- **I. 트래픽 제어 우선**: PASS. Lua script는 idempotency 신규 처리 전에 active admission을 확인하고, 기존 idempotency result 재사용 시에는 최초 결과만 반환한다.
- **II. 처리량보다 정합성 우선**: PASS. 같은 key 재시도와 다른 key 사용자 중복 예약 모두 Redis 원자 처리로 보호한다.
- **III. 로컬 재현성**: PASS. 기존 Docker Compose/Gradle 흐름만 사용한다.
- **IV. 테스트 기반 증거**: PASS. 후속 `speckit-tasks`에서 API/서비스/Redis 통합 테스트를 구현 작업으로 분리해야 한다.
- **V. 문서도 아키텍처의 일부**: PASS. contract, data model, quickstart에 구현 전 설계가 기록되어 있다.
