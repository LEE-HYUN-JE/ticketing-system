<!--
Sync Impact Report
Version change: template -> 1.0.0
Modified principles:
- Placeholder principles -> I. 트래픽 제어 우선
- Placeholder principles -> II. 처리량보다 정합성 우선
- Placeholder principles -> III. 로컬 재현성
- Placeholder principles -> IV. 테스트 기반 증거
- Placeholder principles -> V. 문서도 아키텍처의 일부
Added sections:
- 시스템 제약
- 개발 워크플로
Removed sections:
- Template placeholder sections
Templates requiring updates:
- updated: .specify/templates/plan-template.md reviewed; generic Constitution Check remains compatible
- updated: .specify/templates/spec-template.md reviewed; mandatory scenarios and measurable outcomes remain compatible
- updated: .specify/templates/tasks-template.md reviewed; story-based task organization remains compatible
Follow-up TODOs: none
-->

# Ticketing Traffic Lab Constitution

## Core Principles

### I. 트래픽 제어 우선

모든 사용자 진입 트래픽은 예약 기능에 도달하기 전에 반드시 대기열 입장 흐름을 거쳐야 한다. Admission Scheduler가 발급한 active token 없이 예매 요청이 성공해서는 안 된다. 대기열 등록과 대기 상태 조회는 MySQL을 직접 사용하지 않아야 하며, 트래픽 스파이크는 Redis 계층에서 먼저 흡수해야 한다.

근거: 이 프로젝트의 핵심은 직접 DB로 쓰는 예매 서비스가 아니라, 백프레셔와 입장 제어를 증명하는 것이다.

### II. 처리량보다 정합성 우선

시스템은 초과 예매와 동일 사용자 중복 예매를 허용해서는 안 된다. 동시 요청 관점에서 좌석 선점은 원자적으로 처리되어야 한다. 클라이언트나 부하 테스트 도구가 재시도할 수 있는 모든 변경 요청에는 명시적인 멱등성 규칙이 있어야 한다.

근거: 처리량이 높아도 존재하지 않는 좌석을 판매하거나 결과가 불일치하면 좋은 시스템이라고 볼 수 없다.

### III. 로컬 재현성

로컬 환경은 문서화된 명령과 Docker Compose로 재현 가능해야 한다. 모든 성능 주장은 로컬 머신 조건을 함께 명시해야 하며, 분산 운영 환경에서 측정한 것이 아니라면 로컬 실험 결과로 표현해야 한다. 기능에 필요한 인프라는 숨은 수동 설정 없이 실행 가능해야 한다.

근거: 다른 개발자가 저장소를 clone한 뒤 같은 실험을 실행하고 관찰할 수 있어야 한다.

### IV. 테스트 기반 증거

대기열, 입장 제어, 멱등성, 좌석 선점, 영속화에 영향을 주는 기능은 주요 성공 경로와 정합성을 보호하는 실패 경로에 대한 자동화 테스트를 포함해야 한다. Redis 동작은 통합 테스트 또는 재현 가능한 동등 환경으로 검증해야 한다. 성능 주장은 부하 테스트 결과가 문서화된 뒤에만 작성한다.

근거: 이 프로젝트는 다이어그램이 아니라 동시성 상황에서 증명된 동작으로 평가된다.

### V. 문서도 아키텍처의 일부

저장소는 시스템 흐름, Redis 자료구조, API 계약, 정합성 불변조건, 부하 테스트 해석 규칙을 문서화해야 한다. 주요 설계 결정은 구현 전 또는 구현과 동시에 아키텍처 문서나 ADR에 기록해야 한다. 문서는 현재 구현과 미래 확장 옵션을 구분해야 한다.

근거: 이 프로젝트는 포트폴리오이자 학습 프로젝트이므로, 왜 그렇게 설계했는지 설명하는 능력도 결과물이다.

## 시스템 제약

목표 시나리오는 로컬 환경에서 30,000명의 virtual user와 2,000개 좌석을 가진 명절 기차표 예매 이벤트를 시뮬레이션하는 것이다. 기본 queue polling interval은 5초, 기본 active token TTL은 60초, 기본 admission rate는 초당 300명이다. 기능 명세에서 명시적으로 바꾸지 않는 한 이 값을 기본값으로 사용한다.

대기열의 표준 자료구조는 Redis Sorted Set이다. Redis key는 반드시 event id 기준으로 namespace를 나눈다. 요청 경로와 scheduler 경로에서 Redis `KEYS` 명령어를 사용해서는 안 된다. 좌석 선점은 Redis Lua Script 또는 문서화된 동등한 원자적 Redis 연산으로 처리해야 한다. MySQL은 영속 저장소지만, 대기열 등록과 대기 상태 조회의 hot path에 있어서는 안 된다.

초기 구현은 여러 서비스로 성급히 분리하기보다, 명확한 패키지 경계를 가진 단일 Spring Boot 서비스로 시작하는 것을 권장한다. Redis Cluster, Kafka, RabbitMQ, WebSocket, 다중 애플리케이션 인스턴스 같은 확장 방향은 문서화할 수 있지만, 로컬 실험의 목적을 흐리면 안 된다.

## 개발 워크플로

개발은 SpecKit 흐름에 따라 기능 단위로 진행해야 한다: constitution, specification, 필요한 clarification, plan, tasks, analysis, implementation, validation. 각 기능은 독립적으로 테스트 가능해야 하며, spec, plan, tasks, tests, code를 통해 리뷰할 수 있을 만큼 작아야 한다.

권장 기능 순서는 다음과 같다.

1. 대기열 입장과 active token 전환.
2. 좌석 예매와 원자적 좌석 선점.
3. 재시도 요청에 대한 멱등성.
4. 비동기 영속화 worker.
5. 부하 테스트 스크립트와 결과 문서화.
6. 관찰 가능성과 ADR 정리.

구현 전에 생성된 tasks는 정합성, 테스트 가능성, 문서화, 로컬 재현성을 충분히 다루는지 점검해야 한다. 구현 후에는 테스트와 관련 부하 테스트 스크립트를 실행해야 하며, 실행하지 못했다면 그 이유를 문서화해야 한다.

## Governance

이 constitution은 비공식 선호나 생성된 plan보다 우선한다. spec, plan, tasks, implementation이 이 문서의 MUST 규칙과 충돌하면 구현 전에 해당 산출물을 수정해야 한다.

개정할 때는 이유, 갱신된 버전, 영향을 받을 수 있는 spec, plan, template, docs를 짧게 기록해야 한다. 버전 정책은 semantic versioning을 따른다.

- MAJOR: 원칙 삭제 또는 호환되지 않는 재정의.
- MINOR: 새 원칙 추가 또는 governance의 실질적 확장.
- PATCH: 의무를 바꾸지 않는 문구 정리.

모든 기능 리뷰는 트래픽 제어, 정합성, 로컬 재현성, 테스트 증거, 문서화 요구사항을 확인해야 한다.

**Version**: 1.0.0 | **Ratified**: 2026-05-10 | **Last Amended**: 2026-05-10
