# Quickstart: Queue Admission

이 quickstart는 구현 후 queue admission 기능을 어떻게 실행하고 확인할지 설명합니다.

## 준비물

- Java 21
- Docker
- Docker Compose

## 인프라 실행

```bash
docker compose up -d redis
```

현재 저장소의 최신 브랜치에는 비동기 MySQL 영속화 기능도 포함되어 있으므로 전체 애플리케이션을 실행할 때는 MySQL도 함께 실행한다.

```bash
docker compose up -d redis mysql
```

## 테스트 실행

```bash
./gradlew test
```

기대 테스트 범위:

- 중복 queue entry가 중복 waiting position을 만들지 않는다.
- waiting user에게 rank와 total waiting count가 반환된다.
- admission scheduler가 설정된 rate보다 많은 사용자를 입장시키지 않는다.
- active admission이 설정된 TTL 이후 만료된다.
- queue registration과 status lookup은 MySQL을 필요로 하지 않는다.

## 애플리케이션 실행

```bash
./gradlew bootRun
```

대기열 상태를 수동으로 하나씩 확인할 때는 실행 목적에 맞춰 queue 설정을 override할 수 있다.

```bash
# WAITING 상태를 오래 관찰하고 싶을 때
QUEUE_SCHEDULER_ENABLED=false ./gradlew bootRun

# ENTERED 상태를 빠르게 관찰하고 싶을 때
QUEUE_ADMISSION_RATE_PER_SECOND=20 ./gradlew bootRun
```

## 수동 API 흐름

대기열 진입:

```bash
curl -X POST http://localhost:8080/api/events/holiday-2026/queue \
  -H 'Content-Type: application/json' \
  -d '{"userId":"user-1"}'
```

상태 조회:

```bash
curl http://localhost:8080/api/events/holiday-2026/queue/{queueToken}
```

`jq`가 설치되어 있다면 token을 변수로 저장해 반복 조회할 수 있다.

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/events/holiday-2026/queue \
  -H 'Content-Type: application/json' \
  -d '{"userId":"user-1"}' | jq -r '.queueToken')

curl -s http://localhost:8080/api/events/holiday-2026/queue/$TOKEN | jq
```

기대 상태:

- `WAITING`: 사용자가 아직 대기열에 있다.
- `ENTERED`: 사용자가 active admission을 받아 이후 reservation flow로 진행할 수 있다.
- `EXPIRED`: token 또는 active admission을 더 이상 사용할 수 없다.

### WAITING 검증

다음 설정으로 애플리케이션을 실행한다.

```bash
QUEUE_SCHEDULER_ENABLED=false ./gradlew bootRun
```

요청:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/events/holiday-2026/queue \
  -H 'Content-Type: application/json' \
  -d '{"userId":"waiting-user"}' | jq -r '.queueToken')

curl -s http://localhost:8080/api/events/holiday-2026/queue/$TOKEN | jq
```

기대 응답:

```json
{
  "status": "WAITING",
  "rank": 1,
  "totalWaiting": 1,
  "pollAfterSeconds": 5,
  "activeExpiresInSeconds": null
}
```

### ENTERED 검증

다음 설정으로 애플리케이션을 실행한다.

```bash
QUEUE_ADMISSION_RATE_PER_SECOND=20 ./gradlew bootRun
```

요청:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/events/holiday-2026/queue \
  -H 'Content-Type: application/json' \
  -d '{"userId":"entered-user"}' | jq -r '.queueToken')

sleep 2
curl -s http://localhost:8080/api/events/holiday-2026/queue/$TOKEN | jq
```

기대 응답:

```json
{
  "status": "ENTERED",
  "rank": null,
  "totalWaiting": null,
  "pollAfterSeconds": null,
  "activeExpiresInSeconds": 59
}
```

`activeExpiresInSeconds`는 조회 시점에 따라 달라지므로 0보다 큰 값이면 정상이다.

### EXPIRED 검증

알 수 없는 UUID token은 terminal `EXPIRED` 상태로 응답한다.

```bash
curl -s http://localhost:8080/api/events/holiday-2026/queue/00000000-0000-0000-0000-000000000000 | jq
```

기대 응답:

```json
{
  "status": "EXPIRED",
  "rank": null,
  "totalWaiting": null,
  "pollAfterSeconds": null,
  "activeExpiresInSeconds": null
}
```

## 로컬 부하 테스트 목표

이 프로젝트의 queue-only 부하 테스트는 30,000 virtual user 시나리오만 대상으로 한다.

```text
virtual users: 30,000
polling interval: 5 seconds
admission rate: 300 users/second
```

중복 사용자가 두 번 대기열에 들어가지 않고, Queue API가 `WAITING`, `ENTERED`, `EXPIRED` 상태 polling을 계속 처리하며, active admission이 설정된 rate를 넘지 않으면 queue 기능은 목표 테스트를 통과한 것으로 본다.

실행:

```bash
BASE_URL=http://localhost:8080 k6 run k6-load-test/queue-admission.js
```
