# Tasks: 30,000명 Queue-Only 부하 테스트

**Input**: `/specs/005-load-test/` 아래의 설계 문서  
**Prerequisites**: `plan.md`, `spec.md`, `research.md`, `data-model.md`, `quickstart.md`

**Tests**: 이 기능은 30,000 VU k6 설정 검증, 애플리케이션/인프라 실행, 실제 k6 실행, Redis 상태 점검, 결과 문서화를 포함한다.

**Organization**: 각 작업은 부하 테스트 실행, 결과 기록, 블로그 초안 작성 user story 단위로 구성한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 서로 다른 파일을 수정하고 미완료 작업에 의존하지 않아 병렬 진행 가능
- **[Story]**: `spec.md`의 user story와 연결
- 모든 task는 명확한 대상 파일 경로를 포함한다

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 부하 테스트 실행 전 도구, 디렉터리, 기존 스크립트 설정을 확인한다.

- [X] T001 Verify `k6-load-test/queue-admission.js` defaults to 30,000 VU and 30,000 iterations with `k6 inspect k6-load-test/queue-admission.js`
- [X] T002 [P] Create result artifact directory `docs/load-test-results/`
- [X] T003 [P] Create blog artifact directory `docs/blog/`
- [X] T004 [P] Capture local tool versions for Java, Docker, k6, Redis image, MySQL image in `docs/load-test-results/005-queue-admission-30000.md`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 실제 부하 테스트 실행에 필요한 애플리케이션과 인프라 상태를 준비한다.

- [X] T005 Run `./gradlew test` and record pass/fail in `docs/load-test-results/005-queue-admission-30000.md`
- [X] T006 Run `docker compose up -d redis mysql` and record container status in `docs/load-test-results/005-queue-admission-30000.md`
- [X] T007 Start the Spring Boot application for load testing and record the selected `BASE_URL` in `docs/load-test-results/005-queue-admission-30000.md`

---

## Phase 3: User Story 1 - 30,000명 queue-only 부하 테스트 실행 (Priority: P1) MVP

**Goal**: k6로 30,000명의 virtual user가 Queue API에 진입하고 상태 polling을 수행하게 한다.

**Independent Test**: `k6 run` summary에서 30,000 iterations 설정과 Queue API request 결과를 확인한다.

### Tests for User Story 1

- [X] T008 [P] [US1] Run `k6 inspect k6-load-test/queue-admission.js` and confirm 30,000 VU/iterations in `docs/load-test-results/005-queue-admission-30000.md`

### Implementation for User Story 1

- [X] T009 [US1] Run 30,000 VU queue-only k6 test with summary export to `docs/load-test-results/005-queue-admission-30000-summary.json`
- [X] T010 [US1] Record k6 execution command, status, failure rate, p95 latency, and notable errors in `docs/load-test-results/005-queue-admission-30000.md`

**Checkpoint**: 30,000명 queue-only 부하 테스트 결과가 성공 또는 실패 상태로 명확히 기록되어야 한다.

---

## Phase 4: User Story 2 - 결과와 로컬 조건 기록 (Priority: P2)

**Goal**: 테스트 결과를 재현 가능한 실험 기록으로 남긴다.

**Independent Test**: 결과 문서만 읽어도 어떤 환경에서 어떤 명령으로 어떤 결과가 나왔는지 알 수 있어야 한다.

### Implementation for User Story 2

- [X] T011 [P] [US2] Capture Redis state with `queue-events`, `waiting:{eventId}`, `active-users:{eventId}` and record it in `docs/load-test-results/005-queue-admission-30000.md`
- [X] T012 [P] [US2] Add interpretation, bottleneck candidates, and next experiment notes to `docs/load-test-results/005-queue-admission-30000.md`
- [X] T013 [US2] Update `docs/load-test-plan.md` with a link to the recorded 30,000 user result document

**Checkpoint**: 결과 문서가 실행 조건, 결과, Redis 상태, 해석을 포함해야 한다.

---

## Phase 5: User Story 3 - 블로그 초안 작성 (Priority: P3)

**Goal**: 테스트 결과를 바탕으로 블로그로 옮길 수 있는 실험 노트 초안을 만든다.

**Independent Test**: 블로그 초안이 문제 정의, 설계 선택, 테스트 조건, 결과, 한계, 다음 개선 방향을 포함한다.

### Implementation for User Story 3

- [X] T014 [US3] Create blog draft with problem, architecture, test setup, result, limitations, and next steps in `docs/blog/queue-admission-30000.md`

**Checkpoint**: 블로그 초안은 결과 문서를 근거로 하고 로컬 결과를 운영 성능으로 과장하지 않아야 한다.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 산출물과 SpecKit task 상태를 정리한다.

- [X] T015 Review `docs/load-test-results/005-queue-admission-30000.md` and `docs/blog/queue-admission-30000.md` for Korean documentation consistency
- [X] T016 Mark completed tasks in `specs/005-load-test/tasks.md`
- [X] T017 Commit the 005 load-test artifacts with git

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 즉시 시작 가능
- **Foundational (Phase 2)**: Setup 이후 진행
- **User Story 1 (Phase 3)**: Foundational 이후 진행, MVP
- **User Story 2 (Phase 4)**: US1 결과 이후 진행
- **User Story 3 (Phase 5)**: US2 결과 문서 이후 진행
- **Polish (Phase 6)**: 모든 user story 완료 후 진행

### User Story Dependencies

- **User Story 1 (P1)**: 실제 테스트 실행의 핵심이며 먼저 완료해야 한다.
- **User Story 2 (P2)**: US1의 결과를 해석하고 문서화한다.
- **User Story 3 (P3)**: US2 결과 문서를 바탕으로 작성한다.

## Parallel Opportunities

- T002, T003, T004는 서로 다른 준비 작업이므로 병렬 가능하다.
- T011과 T012는 k6 결과가 나온 뒤 서로 다른 정보 수집/해석 작업으로 병렬 가능하다.

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Setup과 Foundational 작업을 완료한다.
2. k6 inspect로 30,000 VU 설정을 확인한다.
3. 30,000명 queue-only k6 run을 실행한다.
4. 성공 또는 실패 결과를 문서에 기록한다.

### Incremental Delivery

1. 테스트 환경을 재현 가능하게 준비한다.
2. 실제 30,000명 테스트를 실행한다.
3. 결과와 Redis 상태를 기록한다.
4. 블로그 초안으로 실험 의미와 한계를 정리한다.
