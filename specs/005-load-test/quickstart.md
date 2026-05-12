# Quickstart: 30,000명 Queue-Only 부하 테스트

## 준비물

- Java 21
- Docker / Docker Compose
- k6
- Redis CLI

## 테스트 전 확인

```bash
./gradlew test
k6 inspect k6-load-test/queue-admission.js
```

기대값:

- k6 scenario `queue_admission`
- `vus: 30000`
- `iterations: 30000`

## 인프라 실행

```bash
docker compose up -d redis mysql
```

## 애플리케이션 실행

기본 포트가 비어 있으면:

```bash
QUEUE_ADMISSION_RATE_PER_SECOND=300 ./gradlew bootRun
```

8080 포트가 사용 중이면:

```bash
SERVER_PORT=18080 QUEUE_ADMISSION_RATE_PER_SECOND=300 ./gradlew bootRun
```

## 부하 테스트 실행

기본 포트:

```bash
BASE_URL=http://localhost:8080 k6 run --summary-export docs/load-test-results/005-queue-admission-30000-summary.json k6-load-test/queue-admission.js
```

대체 포트:

```bash
BASE_URL=http://localhost:18080 k6 run --summary-export docs/load-test-results/005-queue-admission-30000-summary.json k6-load-test/queue-admission.js
```

## Redis 상태 점검

테스트에 사용한 `EVENT_ID`를 알고 있으면 해당 값을 사용한다. 자동 생성 event id를 사용했다면 k6 실행 로그 또는 결과 문서에 기록한 값을 따른다.

```bash
redis-cli SCARD queue-events
redis-cli ZCARD waiting:{eventId}
redis-cli ZCARD active-users:{eventId}
```

## 결과 문서화

결과는 다음 파일에 기록한다.

```text
docs/load-test-results/005-queue-admission-30000.md
docs/blog/queue-admission-30000.md
```

실패하면 성공으로 포장하지 않고 실패 지점과 병목 후보를 기록한다.
