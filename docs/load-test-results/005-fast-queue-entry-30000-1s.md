# Fast Queue Entry 1초 30,000건 부하 테스트 결과

## 실행 개요

- 테스트 이름: Fast Queue Entry 30,000 requests per 1 second
- 작성일: 2026-05-11
- 실행 시각: 2026-05-11 23:37:59 KST
- 상태: FAILED
- 이유: `http_req_failed` 72.61%, `http_req_duration p95` 10.62s로 threshold를 초과했다.
- 테스트 event id: `loadtest-005-fast-entry-tomcat-30000-1s-20260511233759`
- 대상 URL: `http://localhost:18080`
- 테스트 범위: `POST /api/events/{eventId}/queue`만 측정하며, queue status polling은 포함하지 않는다.

## 실행 조건

10,000/s를 통과한 fast queue entry 구현과 동일한 조건에서 rate만 30,000/s로 올렸다.

```bash
docker compose up -d redis mysql
docker exec ticketing-system-redis-1 redis-cli FLUSHDB
SERVER_PORT=18080 \
QUEUE_SCHEDULER_ENABLED=false \
QUEUE_ADMISSION_RATE_PER_SECOND=300 \
./gradlew bootRun
```

```bash
BASE_URL=http://localhost:18080 \
EVENT_ID=loadtest-005-fast-entry-tomcat-30000-1s-20260511233759 \
RATE=30000 \
DURATION=1s \
PRE_ALLOCATED_VUS=30000 \
MAX_VUS=36000 \
k6 run \
  --quiet \
  --summary-export docs/load-test-results/005-fast-queue-entry-tomcat-30000-1s-summary.json \
  k6-load-test/queue-entry-rate.js
```

## k6 설정 확인

```text
executor: constant-arrival-rate
rate: 30000
timeUnit: 1s
duration: 1s
preAllocatedVUs: 30000
maxVUs: 36000
```

## k6 결과 요약

```text
iterations: 22610
http_reqs: 22610
http request rate: 1369.52/s

checks: 12384 passed / 32836 failed
check success rate: 27.38%

queue entries: 6192
invalid entry responses: 16418
```

HTTP 지표:

```text
http_req_failed: 72.61% (16418 failed / 22610 total)
http_req_duration:
  avg: 5.04s
  med: 5.85s
  p90: 8.93s
  p95: 10.62s
  max: 10.89s
```

대표 에러:

```text
can't assign requested address
EOF
```

## Redis 상태 점검

```bash
docker exec ticketing-system-redis-1 redis-cli SCARD queue-events
docker exec ticketing-system-redis-1 redis-cli ZCARD waiting:loadtest-005-fast-entry-tomcat-30000-1s-20260511233759
docker exec ticketing-system-redis-1 redis-cli ZCARD active-users:loadtest-005-fast-entry-tomcat-30000-1s-20260511233759
docker exec ticketing-system-redis-1 redis-cli INFO memory
```

결과:

```text
queue-events count: 1
waiting count for event: 6192
active-users count for event: 0
Redis used_memory_human: 5.43M
Redis used_memory_peak_human: 13.51M
```

## 10,000/s 통과 결과와 비교

| 항목 | 10,000/s | 30,000/s |
|------|----------|----------|
| 상태 | PASSED | FAILED |
| iterations | 10,068 | 22,610 |
| http_req_failed | 0.00% | 72.61% |
| p95 latency | 825.15ms | 10.62s |
| queue entries | 10,068 | 6,192 |
| 대표 에러 | 없음 | can't assign requested address, EOF |
| Redis waiting count | 10,068 | 6,192 |

## 해석

30,000/s 실패는 단순한 애플리케이션 로직 실패로 보기는 어렵다. 대표 에러가 `can't assign requested address`였기 때문에, k6가 로컬에서 한 번에 너무 많은 outbound TCP connection을 만들다가 ephemeral port 또는 socket 자원 한계에 먼저 부딪힌 것으로 해석하는 편이 타당하다.

또한 실행 중 애플리케이션 로그에 Redis 연결 경고가 함께 관찰됐다.

```text
XREADGROUP error: Unable to connect to Redis
```

이는 단일 로컬 머신에서 부하 발생기, Spring Boot 애플리케이션, Redis, MySQL을 모두 동시에 실행하는 조건이 30,000/s 순간 부하를 안정적으로 분리 측정하기에 적합하지 않다는 신호다.

## 결론

- 현재 구현은 1초 10,000건 queue entry 요청은 통과했다.
- 같은 단일 로컬 환경에서 1초 30,000건은 실패했다.
- 실패 원인은 애플리케이션 hot path만의 문제가 아니라, k6 클라이언트 socket 자원, OS TCP port, Docker Redis, WAS 수용 계층이 함께 얽힌 로컬 한계로 보인다.
- 30,000/s를 계속 목표로 삼으려면 부하 발생기를 별도 머신으로 분리하거나, arrival rate를 ramping 방식으로 나누고, OS TCP 설정과 Redis 배치도 함께 조정해야 한다.
