# Feature Specification: 30,000명 Queue-Only 부하 테스트

**Feature Branch**: `005-load-test`  
**Created**: 2026-05-11  
**Status**: Draft  
**Input**: User description: "30,000명의 사용자가 동시에 대기열에 진입하는 queue-only 부하 테스트를 실행하고, 결과를 문서화한다. 테스트 대상은 Queue API의 대기열 진입과 상태 polling이며, 좌석 예매와 MySQL 영속화 부하 테스트는 포함하지 않는다. k6로 30,000 VU를 실행하고 Queue API latency, 실패율, Redis 대기열/active admission 상태, 로컬 머신 조건을 기록한다. 나중에 블로그로 남길 수 있게 실험 조건과 해석도 정리한다."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 30,000명 queue-only 부하 테스트 실행 (Priority: P1)

개발자는 로컬 환경에서 30,000명의 virtual user가 동시에 Queue API에 진입하고 상태를 polling하는 부하 테스트를 실행한다.

**Why this priority**: 이 프로젝트의 핵심 목표는 대량 진입 트래픽을 Redis 대기열로 흡수하고, 예매 계층으로 직접 흘려보내지 않는 구조를 실제로 관찰하는 것이다.

**Independent Test**: Redis와 애플리케이션을 실행한 뒤 k6 스크립트를 실행하여 30,000 VU, 30,000 iterations 설정이 적용되고 Queue API가 유효한 상태 응답을 반환하는지 확인한다.

**Acceptance Scenarios**:

1. **Given** Redis와 애플리케이션이 실행 중이고 admission rate가 300 users/s로 설정되어 있을 때, **When** k6 30,000 VU queue-only 테스트를 실행하면, **Then** 모든 virtual user는 대기열 진입 요청을 보내고 queue token을 받아 상태 polling을 수행한다.
2. **Given** 테스트가 진행 중일 때, **When** 사용자가 status endpoint를 polling하면, **Then** 응답은 `WAITING`, `ENTERED`, `EXPIRED` 중 하나의 유효한 상태를 반환한다.

---

### User Story 2 - 결과와 로컬 조건 기록 (Priority: P2)

개발자는 부하 테스트 결과를 재현 가능하게 해석하기 위해 로컬 머신 조건, 애플리케이션 설정, k6 요약 결과, Redis 상태를 문서로 남긴다.

**Why this priority**: 성능 결과는 실행 환경 없이는 의미가 약하다. 블로그와 포트폴리오에서 신뢰할 수 있는 실험으로 보이려면 조건과 결과를 함께 남겨야 한다.

**Independent Test**: 테스트 실행 후 결과 문서에 machine, command, k6 summary, failure rate, p95 latency, Redis 상태 점검 결과가 포함되어 있는지 확인한다.

**Acceptance Scenarios**:

1. **Given** 부하 테스트가 완료되었을 때, **When** 결과 문서를 열면, **Then** 실행 명령, 실행 시각, 로컬 머신 조건, Queue API latency, 실패율, Redis key 관찰 결과가 포함되어 있다.
2. **Given** 테스트가 실패하거나 중단되었을 때, **When** 결과 문서를 열면, **Then** 실패 시점과 병목 후보, 다음 실험 제안이 기록되어 있다.

---

### User Story 3 - 블로그 초안 작성 (Priority: P3)

개발자는 테스트 결과를 바탕으로 블로그 글로 옮길 수 있는 실험 노트 초안을 만든다.

**Why this priority**: 이 프로젝트는 학습과 포트폴리오 목적이 있으므로, 단순 결과 숫자보다 설계 의도와 해석을 설명하는 산출물이 중요하다.

**Independent Test**: 블로그 초안 문서가 문제 정의, 설계 선택, 테스트 조건, 결과, 한계, 다음 개선 방향을 포함하는지 확인한다.

**Acceptance Scenarios**:

1. **Given** 부하 테스트 결과가 기록되어 있을 때, **When** 블로그 초안 문서를 작성하면, **Then** 독자가 왜 Redis 대기열을 사용했는지와 30,000명 queue-only 테스트가 무엇을 의미하는지 이해할 수 있다.

---

### Edge Cases

- k6 또는 Docker가 로컬에 설치되어 있지 않으면 실행하지 못한 이유와 필요한 설치 조건을 문서화한다.
- 30,000 VU가 로컬 머신 한계로 실패하면 실패 자체를 병목 관찰 결과로 기록하고, 성공처럼 포장하지 않는다.
- 8080 포트가 이미 사용 중이면 다른 port로 애플리케이션을 실행하고 실제 사용한 `BASE_URL`을 기록한다.
- 테스트 중 일부 token이 `EXPIRED`가 되더라도 queue-only 테스트의 정상 상태로 기록하되, 비정상적인 실패율과 구분한다.
- MySQL은 최신 애플리케이션 기동을 위해 실행할 수 있지만, 이번 부하 테스트의 측정 대상에는 포함하지 않는다.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 시스템은 30,000 VU와 30,000 iterations로 Queue API queue-only k6 테스트를 실행할 수 있어야 한다.
- **FR-002**: 테스트는 `POST /api/events/{eventId}/queue`와 `GET /api/events/{eventId}/queue/{queueToken}`만 호출해야 한다.
- **FR-003**: 테스트는 좌석 예매 API와 MySQL 영속화 부하를 포함하지 않아야 한다.
- **FR-004**: 결과 문서는 실행 명령, 실행 시각, 로컬 머신 조건, 애플리케이션 설정, k6 요약 결과를 포함해야 한다.
- **FR-005**: 결과 문서는 Queue API 실패율, p95 latency, status 응답 분포, Redis waiting/active 상태 관찰 결과를 포함해야 한다.
- **FR-006**: 결과 문서는 성공/실패 여부와 관계없이 병목 후보와 다음 개선 방향을 포함해야 한다.
- **FR-007**: 블로그 초안은 문제 정의, 설계 의도, 테스트 방법, 결과 해석, 한계, 다음 단계로 구성되어야 한다.
- **FR-008**: 문서는 로컬 실험 결과를 운영 환경 성능으로 과장하지 않아야 한다.

### Key Entities *(include if feature involves data)*

- **LoadTestRun**: 하나의 30,000명 queue-only 테스트 실행 기록. 실행 시각, 명령, 설정, 결과 요약, 상태를 가진다.
- **LocalEnvironment**: CPU, memory, OS, Java, Docker, Redis, MySQL, k6 version 등 재현 조건.
- **QueueMetricSnapshot**: 테스트 전후 Redis queue registry, waiting count, active user count, k6 latency/failure summary.
- **BlogDraft**: 실험 결과를 사람이 읽는 글로 정리하기 위한 초안.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: k6 설정 확인 시 기본 실행값은 30,000 VU와 30,000 iterations로 표시된다.
- **SC-002**: 테스트 결과 문서에는 실행 명령, 로컬 환경, Queue API 실패율, p95 latency, Redis 상태 점검 결과가 모두 기록된다.
- **SC-003**: Queue API 호출 실패율과 latency는 k6 summary에서 확인 가능한 형태로 저장된다.
- **SC-004**: 테스트가 실패하더라도 실패 원인, 관찰된 병목, 다음 실험 방향이 결과 문서에 기록된다.
- **SC-005**: 블로그 초안은 실험 조건, 결과, 한계, 다음 개선 방향을 포함한다.

## Assumptions

- 로컬 30,000명 시나리오는 실제 브라우저 30,000개가 아니라 k6 virtual user 테스트다.
- 이번 기능은 queue-only 부하 테스트이며, 좌석 선점과 비동기 MySQL 저장 성능 검증은 포함하지 않는다.
- 최신 애플리케이션은 MySQL datasource를 요구하므로 로컬 실행 시 Redis와 MySQL을 함께 띄운다.
- 테스트 결과가 로컬 머신 한계로 실패해도 의미 있는 실험 결과로 기록한다.
- 블로그 초안은 최종 게시물이 아니라 후속 편집 가능한 실험 노트 형태로 작성한다.
