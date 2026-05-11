# Data Model: 30,000명 Queue-Only 부하 테스트

## LoadTestRun

하나의 30,000명 queue-only 부하 테스트 실행을 나타낸다.

Fields:

- `testName`: 테스트 이름
- `startedAt`: 테스트 시작 시각
- `finishedAt`: 테스트 종료 시각
- `status`: `PASSED`, `FAILED`, `INTERRUPTED`
- `baseUrl`: 테스트 대상 애플리케이션 URL
- `eventId`: 테스트 event id
- `vus`: virtual user 수
- `iterations`: iteration 수
- `pollAfterSeconds`: polling interval
- `admissionRatePerSecond`: active admission 전환 속도
- `command`: 실제 실행 명령
- `summaryPath`: k6 summary 파일 경로

Validation rules:

- `vus`와 `iterations`는 기본 30,000이다.
- `status`는 성공 여부를 과장하지 않고 실제 결과를 반영해야 한다.
- `command`는 재실행 가능하게 기록해야 한다.

## LocalEnvironment

테스트 결과를 해석하기 위한 로컬 실행 조건이다.

Fields:

- `cpu`
- `memory`
- `os`
- `javaVersion`
- `dockerVersion`
- `k6Version`
- `redisImage`
- `mysqlImage`

Validation rules:

- 로컬 환경 정보는 결과 문서에 반드시 포함한다.
- 알 수 없는 값은 빈칸이 아니라 `확인하지 못함`으로 기록한다.

## QueueMetricSnapshot

테스트 전후 Redis와 k6에서 관찰한 queue 상태다.

Fields:

- `queueEventsCount`
- `waitingCount`
- `activeUsersCount`
- `httpReqFailedRate`
- `httpReqDurationP95`
- `queueEntryChecks`
- `queueStatusChecks`
- `notes`

Validation rules:

- Redis key는 테스트 `eventId` 기준으로 조회한다.
- k6 metric은 summary에서 확인 가능한 값만 기록한다.

## BlogDraft

실험 결과를 블로그 글로 옮기기 위한 초안이다.

Fields:

- `title`
- `problem`
- `architectureSummary`
- `testSetup`
- `resultSummary`
- `interpretation`
- `limitations`
- `nextSteps`

Validation rules:

- 로컬 테스트 결과를 운영 성능처럼 표현하지 않는다.
- 실패 결과가 있으면 실패를 숨기지 않고 해석에 포함한다.
