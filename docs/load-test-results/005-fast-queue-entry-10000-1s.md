# Fast Queue Entry 1초 10,000건 부하 테스트 결과

## 실행 개요

- 테스트 이름: Fast Queue Entry 10,000 requests per 1 second
- 작성일: 2026-05-11
- 실행 시각: 2026-05-11 22:51:52 KST
- 상태: PASSED
- 이유: `http_req_failed` 0.00%, `http_req_duration p95` 825.15ms로 threshold를 통과했다.
- 테스트 event id: `loadtest-005-fast-entry-tomcat-10000-1s-20260511225152`
- 대상 URL: `http://localhost:18080`
- 테스트 범위: `POST /api/events/{eventId}/queue`만 측정하며, queue status polling은 포함하지 않는다.

## 적용한 개선

### 1. Queue entry hot path 축소

`POST /queue`는 더 이상 첫 응답에서 `rank`, `totalWaiting`을 계산하지 않는다.

```json
{
  "queueToken": "...",
  "status": "WAITING",
  "rank": null,
  "totalWaiting": null,
  "pollAfterSeconds": 5
}
```

순번 계산은 `GET /queue/{queueToken}` 상태 조회에서만 수행한다.

### 2. Redis 등록 원자화

신규 진입 등록은 `register_queue_entry.lua`로 묶었다.

- 같은 `eventId/userId`가 이미 token을 갖고 있으면 기존 token을 반환한다.
- 신규 사용자면 token mapping, reverse index, waiting ZSET, event registry를 한 번의 Redis script로 등록한다.
- 동일 사용자의 동시 중복 진입은 하나의 token과 하나의 waiting member로 수렴한다.

### 3. Tomcat 수용 계층 명시 설정

기본 embedded Tomcat 설정 대신 부하 테스트 기준값을 명시했다.

```yaml
server:
  tomcat:
    threads:
      max: 400
      min-spare: 50
    max-connections: 20000
    accept-count: 10000
    connection-timeout: 5s
```

## 실행 명령

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
EVENT_ID=loadtest-005-fast-entry-tomcat-10000-1s-20260511225152 \
RATE=10000 \
DURATION=1s \
PRE_ALLOCATED_VUS=10000 \
MAX_VUS=12000 \
k6 run \
  --quiet \
  --summary-export docs/load-test-results/005-fast-queue-entry-tomcat-10000-1s-summary.json \
  k6-load-test/queue-entry-rate.js
```

## k6 결과 요약

```text
iterations: 10068
http_reqs: 10068
http request rate: 5555.87/s

checks: 20136 passed / 0 failed
check success rate: 100.00%

queue entries: 10068
```

HTTP 지표:

```text
http_req_failed: 0.00% (0 failed / 10068 total)
http_req_duration:
  avg: 684.23ms
  med: 699.54ms
  p90: 806.45ms
  p95: 825.15ms
  max: 885.10ms
```

## Redis 상태 점검

```bash
docker exec ticketing-system-redis-1 redis-cli SCARD queue-events
docker exec ticketing-system-redis-1 redis-cli ZCARD waiting:loadtest-005-fast-entry-tomcat-10000-1s-20260511225152
docker exec ticketing-system-redis-1 redis-cli ZCARD active-users:loadtest-005-fast-entry-tomcat-10000-1s-20260511225152
docker exec ticketing-system-redis-1 redis-cli INFO memory
```

결과:

```text
queue-events count: 1
waiting count for event: 10068
active-users count for event: 0
Redis used_memory_human: 7.86M
Redis used_memory_peak_human: 13.51M
```

## 개선 전후 비교

| 항목 | 기존 entry-only | hot path만 개선 | hot path + Tomcat 설정 |
|------|------------------|-----------------|-------------------------|
| Redis 상태 | 이전 테스트 데이터 존재 | Redis 초기화, scheduler off | Redis 초기화, scheduler off |
| Tomcat 설정 | 기본값 | 기본값 | 명시 튜닝 |
| 실패율 | 23.81% | 55.09% | 0.00% |
| p95 latency | 1.18s | 1.62s | 825.15ms |
| queue entries | 7,628 | 4,493 | 10,068 |
| threshold | FAILED | FAILED | PASSED |

hot path만 줄였을 때는 기대와 달리 실패율이 개선되지 않았다. Redis Lua script는 원자성을 보장하지만, 단일 Redis thread에서 더 큰 script를 직렬 실행하게 되므로 10,000/s burst 상황에서는 Tomcat 수용 계층 설정이 함께 필요했다.

최종적으로 Tomcat `threads.max`, `max-connections`, `accept-count`를 명시하자 connection reset이 사라졌고, 동일한 1초 10,000 entry-only 조건을 통과했다.

## 해석

이번 결과는 병목이 하나가 아니라는 점을 보여준다.

- 첫 응답에서 순번 계산을 제거해 API hot path를 단순화했다.
- Lua script로 중복 진입 원자성을 확보했다.
- Tomcat connection/thread/backlog 설정을 명시해 순간 연결 수용 능력을 확보했다.

즉 성공 요인은 “Redis 최적화 하나”가 아니라, API hot path와 WAS 수용 계층을 함께 정리한 것이다.

## 로그와 관측성 판단

요청마다 `INFO` 로그를 남기는 방식은 부하 테스트와 운영 환경 모두에 해롭다. 1초 10,000 요청 조건에서 request log를 동기 출력하면 로그 I/O가 새로운 병목이 된다.

대신 이번 프로젝트에서는 다음 관측 자료를 남기는 방식을 선택했다.

- k6 summary JSON
- Redis queue cardinality 검증
- 실패율, p95 latency, queue entry count 비교표
- 동시 중복 진입 통합 테스트
- 블로그용 실험 해석 문서

이 방식이 토이 프로젝트의 완성도를 보여주기에는 더 낫다. 필요한 경우 운영 로그는 요청 단위가 아니라 scheduler tick, threshold breach, unexpected Redis script failure 같은 이벤트 중심으로 제한하는 것이 좋다.
