# Implementation Plan: Queue Admission

**Branch**: `001-queue-admission` | **Date**: 2026-05-10 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `specs/001-queue-admission/spec.md`

## Summary

Ticketing Traffic Lab의 첫 번째 트래픽 제어 기능을 구현한다. 사용자는 예매 이벤트에 진입할 때 Redis 기반 waiting queue에 등록되고, queue status를 polling하며, 내부 scheduler가 설정된 admission rate에 따라 active 상태로 전환한다.

이 기능은 의도적으로 대기열 등록과 상태 조회에서 MySQL을 사용하지 않는다. 이후 좌석 예매, 멱등성, 비동기 영속화 기능이 의존할 Redis key 모델, API 계약, scheduler 동작, 테스트 기준을 먼저 확립한다.

## Technical Context

**Language/Version**: Java 21  
**Primary Dependencies**: Spring Boot 3, Spring Web, Spring Data Redis, Lettuce, Validation, Actuator  
**Storage**: 대기열 상태는 Redis 사용; 이 기능에서 MySQL은 사용하지 않음  
**Testing**: JUnit 5, AssertJ, Spring Boot Test, Testcontainers Redis  
**Target Platform**: Docker Compose를 사용하는 로컬 macOS 개발 환경  
**Project Type**: Backend web service  
**Performance Goals**: 5초 polling 기준 30,000 virtual-user 대기열 진입 시나리오를 지원하고, queue path에서 DB 접근을 방지한다.  
**Constraints**: 기본 admission rate 300 users/s, 기본 active token TTL 60s, 기본 polling hint 5s, 요청 및 scheduler 경로에서 Redis KEYS 금지  
**Scale/Scope**: 첫 기능은 단일 Spring Boot 애플리케이션으로 구현하되, 이후 queue, reservation, worker 책임을 분리할 수 있도록 패키지 경계를 유지한다.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **트래픽 제어 우선**: PASS. 모든 진입 트래픽은 Queue API와 active admission을 거친다.
- **처리량보다 정합성 우선**: PASS. 중복 대기 등록과 입장 순서가 요구사항과 테스트 대상에 포함되어 있다.
- **로컬 재현성**: PASS. Docker Compose와 Testcontainers를 사용한다.
- **테스트 기반 증거**: PASS. queue 동작과 scheduler 경계 조건에 대한 단위 테스트와 Redis 통합 테스트를 요구한다.
- **문서도 아키텍처의 일부**: PASS. 구현 전 spec, plan, research, data model, contract, quickstart를 만든다.

Post-design re-check: PASS. 생성된 설계 산출물은 queue-only scope, Redis 기반 트래픽 제어, 측정 가능한 검증 기준을 유지한다.

## Project Structure

### Documentation (this feature)

```text
specs/001-queue-admission/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── queue-api.yaml
└── tasks.md
```

### Source Code (repository root)

```text
src/main/java/com/example/ticketing/
├── TicketingSystemApplication.java
├── common/
│   ├── config/
│   └── error/
└── queue/
    ├── api/
    ├── application/
    ├── domain/
    └── infrastructure/

src/main/resources/
├── application.yml
└── lua/

src/test/java/com/example/ticketing/
├── queue/
│   ├── application/
│   └── integration/
└── support/

src/test/resources/
└── application-test.yml

docker-compose.yml
```

**Structure Decision**: 로컬 실험을 위해 하나의 Spring Boot 서비스를 사용한다. 단, 이후 seat reservation, idempotency, persistence worker 기능을 섞지 않도록 queue 코드는 전용 패키지에 둔다.

## Complexity Tracking

정당화가 필요한 constitution 위반 사항은 없다.
