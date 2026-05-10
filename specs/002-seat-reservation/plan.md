# Implementation Plan: Seat Reservation

**Branch**: `002-seat-reservation` | **Date**: 2026-05-10 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `specs/002-seat-reservation/spec.md`

## Summary

active admission을 받은 사용자만 특정 예매 이벤트의 좌석을 선점할 수 있는 Reservation API를 구현한다. 좌석 선점은 Redis Lua Script 하나에서 active admission 확인, 사용자별 기존 예약 확인, 좌석 점유 확인, 결과 기록을 원자적으로 처리한다.

이번 기능은 MySQL 영속화와 full Idempotency-Key 처리를 직접 구현하지 않는다. 대신 Redis에 사용자별 reservation result를 남겨 후속 비동기 영속화 기능이 읽을 수 있는 경계를 만든다.

## Technical Context

**Language/Version**: Java 21  
**Primary Dependencies**: Spring Boot 3, Spring Web, Spring Data Redis, Lettuce, Validation, Actuator  
**Storage**: 좌석 선점과 예매 결과는 Redis 사용; 이번 기능에서 MySQL은 사용하지 않음  
**Testing**: JUnit 5, AssertJ, Spring Boot Test, MockMvc, Testcontainers Redis  
**Target Platform**: Docker Compose를 사용하는 로컬 macOS 개발 환경  
**Project Type**: Backend web service  
**Performance Goals**: 단일 좌석 선점 요청은 Redis Lua Script 1회 실행으로 완료하고, 같은 좌석/사용자 중복 선점이 동시 요청에서도 발생하지 않게 한다.  
**Constraints**: active admission 없이는 RESERVED 불가, 좌석 선점 hot path에서 MySQL 금지, Redis `KEYS` 금지, 기본 좌석 범위 `seat-1`부터 `seat-2000`  
**Scale/Scope**: 단일 Spring Boot 애플리케이션에 reservation package를 추가한다. queue package의 `ActiveAdmissionGuard` 또는 동일 Redis active key 계약을 재사용한다.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **트래픽 제어 우선**: PASS. Reservation API는 active admission이 없으면 좌석 선점을 성공시키지 않는다.
- **처리량보다 정합성 우선**: PASS. 좌석 선점은 Redis Lua Script로 원자 처리하고, 동일 좌석/동일 사용자 중복 예매를 막는다.
- **로컬 재현성**: PASS. 기존 Docker Compose Redis와 Testcontainers Redis 기반 테스트를 유지한다.
- **테스트 기반 증거**: PASS. active admission 실패, 좌석 중복 선점, 사용자 중복 예매, 결과 조회를 자동화 테스트로 검증한다.
- **문서도 아키텍처의 일부**: PASS. Redis key model, API 계약, quickstart를 feature 산출물에 남긴다.

Post-design re-check: PASS. 설계는 단일 Spring Boot 서비스, Redis Lua 원자성, no-MySQL hot path, 문서화 요구를 유지한다.

## Project Structure

### Documentation (this feature)

```text
specs/002-seat-reservation/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── reservation-api.yaml
└── tasks.md
```

### Source Code (repository root)

```text
src/main/java/com/example/ticketing/
├── queue/
│   └── application/
│       └── ActiveAdmissionGuard.java
└── reservation/
    ├── api/
    ├── application/
    ├── domain/
    └── infrastructure/

src/main/resources/
└── lua/
    └── claim_seat.lua

src/test/java/com/example/ticketing/
├── reservation/
│   ├── application/
│   ├── infrastructure/
│   └── integration/
└── support/
```

**Structure Decision**: queue와 reservation은 같은 Spring Boot 애플리케이션 안에서 패키지를 분리한다. reservation은 queue의 active admission Redis key 계약을 검증에 사용하지만, 대기열 등록/상태 조회 책임을 가져오지 않는다.

**Lua Atomicity Decision**: 좌석 선점의 정합성 조건은 Java에서 여러 Redis 명령으로 나누지 않고 `claim_seat.lua` 하나에서 검사하고 기록한다.

**Persistence Boundary Decision**: 이번 기능은 Redis reservation result까지만 기록한다. MySQL 저장과 event 소비는 `004-async-persistence`에서 다룬다.

## Complexity Tracking

정당화가 필요한 constitution 위반 사항은 없다.

