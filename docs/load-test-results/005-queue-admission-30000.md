# 30,000명 Queue-Only 부하 테스트 결과

## 실행 개요

- 테스트 이름: 30,000명 Queue-Only Queue Admission
- 작성일: 2026-05-11
- 실행 시각: 2026-05-11 22:12:13 KST
- 상태: FAILED
- 이유: k6 threshold 실패. `http_req_failed` 48.90%, `http_req_duration p95` 10.46s로 목표를 초과했다.
- 테스트 event id: `loadtest-005-30000-20260511221213`
- 대상 URL: `http://localhost:18080`

## 로컬 환경

```text
OS: macOS 15.4.1
CPU: Apple M1 Pro
Memory: 32 GB
Java: openjdk version "21.0.8" 2025-07-15 LTS
Docker: Docker version 28.5.1, build e180ab8
k6: k6 v1.6.1 (commit/devel, go1.26.0, darwin/arm64)
Redis image: redis:7.4-alpine
MySQL image: mysql:8.0
```

## 실행 명령

사전 검증:

```bash
./gradlew test
k6 inspect k6-load-test/queue-admission.js
docker compose up -d redis mysql
SERVER_PORT=18080 QUEUE_ADMISSION_RATE_PER_SECOND=300 ./gradlew bootRun
```

부하 테스트:

```bash
BASE_URL=http://localhost:18080 \
EVENT_ID=loadtest-005-30000-20260511221213 \
MAX_POLLS=30 \
k6 run \
  --summary-export docs/load-test-results/005-queue-admission-30000-summary.json \
  k6-load-test/queue-admission.js
```

## k6 설정 확인

`k6 inspect k6-load-test/queue-admission.js` 기준:

```text
executor: shared-iterations
vus: 30000
iterations: 30000
maxDuration: 10m
thresholds:
  http_req_failed: rate < 0.05
  http_req_duration: p95 < 2000ms
```

## k6 결과 요약

```text
iterations: 30000
http_reqs: 44590
iteration rate: 438.53/s
http request rate: 651.80/s

checks: 45564 passed / 43616 failed
check success rate: 51.09%

queue entries: 8192
WAITING responses: 6398
ENTERED responses: 8192
EXPIRED responses: 0
invalid status responses: 0
```

HTTP 지표:

```text
http_req_failed: 48.90% (21808 failed / 44590 total)
http_req_duration:
  avg: 2330.27ms
  med: 80.81ms
  p90: 10197.81ms
  p95: 10458.15ms
  max: 56539.12ms

http_req_blocked:
  avg: 2008.26ms
  p95: 9775.94ms

http_req_connecting:
  avg: 2007.35ms
  p95: 9761.54ms

http_req_waiting:
  avg: 2316.35ms
  p95: 10406.19ms
```

대표 에러:

```text
connection reset by peer
can't assign requested address
i/o timeout
request timeout
```

## Redis 상태 점검

로컬에 `redis-cli`가 없어서 Docker 컨테이너 안의 `redis-cli`로 확인했다.

```bash
docker exec ticketing-system-redis-1 redis-cli SCARD queue-events
docker exec ticketing-system-redis-1 redis-cli SISMEMBER queue-events loadtest-005-30000-20260511221213
docker exec ticketing-system-redis-1 redis-cli ZCARD waiting:loadtest-005-30000-20260511221213
docker exec ticketing-system-redis-1 redis-cli ZCARD active-users:loadtest-005-30000-20260511221213
docker exec ticketing-system-redis-1 redis-cli INFO memory
```

결과:

```text
queue-events count: 2
event registered: 1
waiting count for event: 0
active-users count for event: 8301
Redis used_memory_human: 6.31M
Redis used_memory_peak_human: 7.09M
```

## 해석

이번 실행은 30,000 VU 테스트가 끝까지 완료되긴 했지만 성공 기준은 통과하지 못했다. 중요한 관찰은 애플리케이션 내부의 queue 로직보다 먼저 로컬 연결 계층이 한계에 도달했다는 점이다.

`http_req_blocked`와 `http_req_connecting`의 p95가 약 9.7초로 높고, 에러도 `can't assign requested address`, `connection reset by peer`, `i/o timeout`이 중심이었다. 즉 Redis Sorted Set이나 scheduler 자체의 한계라기보다, 단일 로컬 머신에서 30,000 VU가 동시에 localhost 연결을 만들 때 OS ephemeral port, socket backlog, Tomcat accept/thread 처리, k6 local executor가 먼저 병목이 된 것으로 보는 편이 타당하다.

Queue API가 성공적으로 받은 진입은 8,192건이고, 이 사용자는 모두 최종적으로 `ENTERED` 상태까지 관찰됐다. Redis 기준으로 waiting queue는 0으로 비었고, active 관찰 set에는 8,301명이 남아 있었다. 이 차이는 이전 수동 검증/다른 event의 active 관찰 값 또는 TTL 정리 타이밍이 섞였을 수 있으므로, 다음 실험에서는 Redis를 비우거나 event별 관찰만 더 엄격히 분리하는 편이 좋다.

## 결론

- 30,000 VU queue-only 테스트는 실행 자체는 완료됐다.
- 현재 로컬 설정에서는 성공 기준을 통과하지 못했다.
- 병목 후보 1순위는 Redis 자료구조가 아니라 로컬 연결 생성/수용 계층이다.
- 이 결과는 실패가 아니라 다음 튜닝 지점을 보여주는 기준선으로 기록한다.

## 다음 실험 제안

1. k6의 30,000 VU를 한 번에 시작하지 않고 ramping 방식으로 동일 총량을 보낸다.
2. macOS ephemeral port와 file descriptor limit을 확인하고 테스트 전 조건으로 문서화한다.
3. Spring Boot/Tomcat accept count, max connections, thread pool 설정을 명시적으로 조정한다.
4. k6 부하 발생기를 애플리케이션과 다른 머신 또는 컨테이너로 분리한다.
5. Redis key는 테스트 전 `EVENT_ID` 기준으로 정리하거나 Redis DB를 분리해 관찰 값을 더 깨끗하게 만든다.
