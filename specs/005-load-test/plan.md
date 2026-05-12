# Implementation Plan: 30,000명 Queue-Only 부하 테스트

**Branch**: `005-load-test` | **Date**: 2026-05-11 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `specs/005-load-test/spec.md`

## Summary

30,000명의 virtual user가 동시에 Queue API에 진입하고 상태를 polling하는 queue-only 부하 테스트를 실행한다. 좌석 예매와 MySQL 영속화 성능은 이번 범위에서 제외하고, Redis 대기열과 active admission 경로가 로컬 환경에서 어떻게 동작하는지 관찰한다.

결과는 단순한 k6 출력으로 끝내지 않고, 로컬 머신 조건, 실행 명령, k6 요약, Redis 상태 점검, 병목 후보, 다음 실험 방향까지 문서화한다. 이후 블로그로 옮길 수 있도록 별도 실험 노트 초안도 작성한다.

## Technical Context

**Language/Version**: Java 21, JavaScript(k6 script)  
**Primary Dependencies**: Spring Boot 3, Redis, MySQL, Docker Compose, k6  
**Storage**: Redis queue state; MySQL은 애플리케이션 기동 의존성으로 실행하지만 테스트 측정 대상은 아님  
**Testing**: `./gradlew test`, `k6 inspect`, `k6 run`, Redis CLI 상태 점검  
**Target Platform**: 로컬 macOS 개발 환경  
**Project Type**: Backend web service + local load-test artifact  
**Performance Goals**: 30,000 VU / 30,000 iterations queue-only 테스트 실행 결과를 기록한다.  
**Constraints**: Queue API만 호출한다. Reservation API, 좌석 선점, MySQL 영속화 부하 테스트는 포함하지 않는다. 로컬 결과를 운영 성능으로 과장하지 않는다.  
**Scale/Scope**: 30,000 virtual users, admission rate 300 users/s, polling interval 5s, 단일 Spring Boot 애플리케이션, Redis/MySQL Docker Compose.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **트래픽 제어 우선**: PASS. 테스트 대상은 Queue API와 active admission이며 예매 계층으로 직접 트래픽을 보내지 않는다.
- **처리량보다 정합성 우선**: PASS. 이번 테스트는 좌석 정합성보다 queue 상태 유효성과 트래픽 제어 경로 관찰에 집중한다.
- **로컬 재현성**: PASS. Docker Compose, k6, 명령어, 로컬 머신 조건을 문서화한다.
- **테스트 기반 증거**: PASS. 자동 테스트, k6 inspect, k6 run 결과, Redis 상태 점검을 증거로 남긴다.
- **문서도 아키텍처의 일부**: PASS. 결과 문서와 블로그 초안을 산출물로 포함한다.

Post-design re-check: PASS. 설계 산출물은 queue-only 범위, Redis 기반 트래픽 제어, 로컬 재현성, 결과 문서화 요구를 유지한다.

## Project Structure

### Documentation (this feature)

```text
specs/005-load-test/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── checklists/
│   └── requirements.md
└── tasks.md
```

### Source Code / Artifacts (repository root)

```text
k6-load-test/
└── queue-admission.js

docs/
├── load-test-plan.md
├── load-test-results/
│   └── 005-queue-admission-30000.md
└── blog/
    └── queue-admission-30000.md
```

**Structure Decision**: 기존 k6 스크립트를 그대로 사용하고, 테스트 실행 결과와 블로그 초안은 `docs/` 아래에 사람이 읽는 문서로 남긴다. 결과 문서는 실험 증거이고, 블로그 초안은 해석과 이야기 구조를 위한 별도 산출물이다.

## Complexity Tracking

정당화가 필요한 constitution 위반 사항은 없다.
