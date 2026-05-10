# Feature Specification: Queue Admission

**Feature Branch**: `001-queue-admission`  
**Created**: 2026-05-10  
**Status**: Draft  
**Input**: User description: "명절 기차표 예매 시스템의 첫 번째 기능으로 대기열 입장 기능을 만든다. 30,000명의 사용자가 동시에 예매 진입 버튼을 누르는 상황에서 Redis Sorted Set 대기열과 admission scheduler로 트래픽을 제어한다."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 대기열 진입 (Priority: P1)

사용자는 명절 기차표 예매 이벤트에 진입할 때 바로 좌석 예매로 이동하지 않고, queue token을 발급받은 뒤 대기열에 등록된다.

**Why this priority**: 이 기능은 시스템의 트래픽 제어 진입점이다. 이 흐름이 없으면 초기 트래픽 스파이크가 예매 계층과 DB를 직접 압박한다.

**Independent Test**: 동일 이벤트에 여러 사용자의 대기열 진입 요청을 보내고, 각 사용자가 queue token을 받으며 예매 계층과 MySQL이 사용되지 않는지 확인한다.

**Acceptance Scenarios**:

1. **Given** 열려 있는 예매 이벤트와 아직 대기열에 없는 사용자, **When** 사용자가 이벤트 진입을 요청하면, **Then** 사용자는 queue token을 받고 WAITING 상태로 등록된다.
2. **Given** 동일 이벤트에 이미 대기 중인 사용자, **When** 사용자가 다시 진입을 요청하면, **Then** 중복 대기 순번을 만들지 않고 기존 대기 상태를 반환한다.
3. **Given** 같은 이벤트에 서로 다른 시각에 진입한 사용자들, **When** 대기 순번을 계산하면, **Then** 먼저 요청한 사용자가 뒤에 요청한 사용자보다 앞선 순번을 가진다.

---

### User Story 2 - 대기 상태 조회 (Priority: P2)

대기 중인 사용자는 queue token으로 자신의 상태를 polling하여 아직 대기 중인지, 입장 가능한지, 만료되었는지 확인할 수 있다.

**Why this priority**: 상태 조회는 사용자가 예매 API를 직접 두드리지 않고도 대기열 진행 상황을 관찰하게 해준다. 부하 테스트에서도 대기열 동작을 검증하는 주요 관찰 지점이다.

**Independent Test**: 사용자를 대기열에 등록하고 queue token으로 상태를 조회하여 WAITING, ENTERED, EXPIRED 상태와 각 상태에 맞는 필드가 반환되는지 확인한다.

**Acceptance Scenarios**:

1. **Given** 대기 중인 사용자의 유효한 queue token, **When** 사용자가 대기 상태를 조회하면, **Then** 응답은 WAITING 상태, 현재 순번, 전체 대기자 수, 5초 poll-after 힌트를 포함한다.
2. **Given** active admission을 받은 사용자의 유효한 queue token, **When** 사용자가 대기 상태를 조회하면, **Then** 응답은 ENTERED 상태와 active token의 남은 TTL을 포함한다.
3. **Given** 만료되었거나 알 수 없는 queue token, **When** 사용자가 대기 상태를 조회하면, **Then** 응답은 EXPIRED 또는 이에 준하는 terminal non-active 상태를 반환한다.

---

### User Story 3 - 제한된 속도로 사용자 입장 (Priority: P3)

시스템은 scheduler를 통해 오래 기다린 사용자부터 설정된 admission rate만큼 active 상태로 전환한다.

**Why this priority**: controlled admission은 대기열과 예매 계층을 연결하는 백프레셔 장치다.

**Independent Test**: 대기열에 많은 사용자를 넣고 scheduler를 알려진 시간 동안 실행한 뒤, 설정된 수보다 많은 사용자가 active 상태로 전환되지 않는지 확인한다.

**Acceptance Scenarios**:

1. **Given** admission rate보다 많은 대기 사용자가 있을 때, **When** scheduler가 1초 동안 실행되면, **Then** 오래 기다린 사용자부터 설정된 수만 active 상태가 된다.
2. **Given** active 상태가 된 사용자가 TTL 안에 다음 예매 단계로 진행하지 않을 때, **When** TTL이 지나면, **Then** 사용자의 active 상태는 자동 만료된다.
3. **Given** 대기열이 비어 있을 때, **When** scheduler가 실행되면, **Then** 입장 처리 없이 정상 종료된다.

### Edge Cases

- 동일 이벤트와 동일 사용자의 중복 진입 요청은 여러 대기 순번을 만들면 안 된다.
- 유효하지 않거나 알 수 없거나 만료된 queue token은 active admission을 받을 수 없다.
- polling 사이에 사용자가 WAITING에서 ENTERED로 바뀌는 상황을 처리해야 한다.
- scheduler는 빈 대기열에서도 오류 없이 동작해야 한다.
- event id 누락, user id 누락, 잘못된 queue token 형식은 명확한 실패 응답을 받아야 한다.
- 대기 사용자 수가 admission rate보다 적으면 존재하는 사용자만 입장시킨다.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 시스템은 event id와 user id로 특정 예매 이벤트에 진입 요청을 받을 수 있어야 한다.
- **FR-002**: 시스템은 수락된 진입 요청에 대해 queue token을 발급해야 한다.
- **FR-003**: 시스템은 수락된 사용자를 요청 시각 기준으로 정렬되는 이벤트별 대기열에 등록해야 한다.
- **FR-004**: 시스템은 동일 event id와 user id 조합에 대해 중복 대기 순번을 만들면 안 된다.
- **FR-005**: 시스템은 queue token으로 대기 상태를 조회할 수 있어야 한다.
- **FR-006**: 시스템은 아직 대기 중인 사용자에게 WAITING 상태, 현재 순번, 전체 대기자 수, 5초 poll-after 힌트를 반환해야 한다.
- **FR-007**: 시스템은 scheduler에 의해 입장한 사용자에게 ENTERED 상태와 active token 만료 정보를 반환해야 한다.
- **FR-008**: 시스템은 알 수 없거나 만료된 queue token에 대해 EXPIRED 또는 이에 준하는 terminal non-active 상태를 반환해야 한다.
- **FR-009**: 시스템은 오래 기다린 사용자부터 입장시켜야 한다.
- **FR-010**: 시스템은 admission rate를 설정할 수 있어야 하며, 기본값은 초당 300명이다.
- **FR-011**: 시스템은 active admission에 기본 60초 TTL을 적용해야 한다.
- **FR-012**: 시스템은 후속 예매 기능이 재사용할 수 있는 active admission 검증 컴포넌트를 제공해야 하며, active admission이 없는 event/user 조합은 예매 가능 사용자로 판정되면 안 된다.
- **FR-013**: 시스템은 대기열 등록과 대기 상태 조회에서 RDB 접근을 피해야 한다.
- **FR-014**: 시스템은 30,000 virtual user가 대기열에 진입하는 목표 부하 테스트 시나리오를 지원해야 한다.
- **FR-015**: 시스템은 테스트가 queue registration 수, active admission 수, 현재 waiting 수, 현재 active 수, EXPIRED 상태로 판정된 조회 수를 집계할 수 있는 결과 정보를 제공해야 한다.

### Key Entities *(include if feature involves data)*

- **Reservation Event**: 격리된 대기열과 active admission set을 가진 기차표 예매 이벤트.
- **Queue Entry**: 하나의 예매 이벤트 안에서 사용자의 대기 위치를 나타내며, 요청 수락 시각 기준으로 정렬된다.
- **Queue Token**: 한 event와 한 user의 상태 조회를 연결하는 클라이언트 보유 식별자.
- **Active Admission**: TTL 동안 한 event와 한 user가 예매 단계로 진행할 수 있음을 나타내는 임시 권한.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 문서화된 로컬 머신 조건에서 30,000 virtual user가 하나의 이벤트에 진입하는 queue-only 부하 테스트를 실행할 수 있으며, 대기열 등록과 상태 조회는 MySQL을 호출하지 않는다.
- **SC-002**: 동일 event id와 user id의 중복 진입 시도는 중복 대기 순번 0건을 보장한다.
- **SC-003**: WAITING 상태 응답은 순번, 전체 대기자 수, 5초 poll-after 힌트를 포함한다.
- **SC-004**: 기본 admission rate에서 scheduler는 1초 구간에 300명을 초과하여 입장시키지 않는다.
- **SC-005**: active admission은 downstream 예매 단계로 사용되지 않으면 60초 후 자동 만료된다.
- **SC-006**: 서로 다른 요청 시각을 가진 사용자의 oldest-first 입장 순서가 보존된다.

## Assumptions

- 첫 번째 기능은 대기열 입장만 다룬다. 좌석 선택, 결제, 예매 영속화, 멱등성 있는 예매 요청은 이후 기능에서 다룬다.
- 첫 번째 기능은 좌석 예매 API를 구현하지 않지만, 후속 예매 API가 호출할 active admission 검증 컴포넌트는 포함한다.
- queue token은 UUID로 표현한다.
- 기본 polling interval은 5초다.
- 기본 active token TTL은 60초다.
- 기본 admission rate는 초당 300명이다.
- 로컬 30,000명 시나리오는 30,000개의 실제 브라우저가 아니라 virtual user 부하 테스트다.
- EXPIRED 상태 집계는 Redis TTL로 삭제된 key의 총량이 아니라, 상태 조회 또는 검증 과정에서 EXPIRED로 판정된 횟수를 의미한다.
- 동일 timestamp가 발생할 경우의 보조 정렬 기준은 plan 단계에서 결정할 수 있다.
