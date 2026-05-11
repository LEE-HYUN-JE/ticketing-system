# 1초 10,000건 Queue Entry 부하 테스트 결과

## 실행 개요

- 테스트 이름: Queue Entry 10,000 requests per 1 second
- 작성일: 2026-05-11
- 실행 시각: 2026-05-11 22:36:37 KST
- 상태: FAILED
- 이유: `http_req_duration p95`는 1.18s로 통과했지만, `http_req_failed`가 23.81%로 목표를 초과했다.
- 테스트 event id: `loadtest-005-entry-10000-1s-20260511223637`
- 대상 URL: `http://localhost:18080`
- 테스트 범위: `POST /api/events/{eventId}/queue`만 측정하며, queue status polling은 포함하지 않는다.

## 실행 명령

```bash
k6 inspect \
  -e RATE=10000 \
  -e DURATION=1s \
  -e PRE_ALLOCATED_VUS=10000 \
  -e MAX_VUS=12000 \
  k6-load-test/queue-entry-rate.js
```

```bash
docker compose up -d redis mysql
SERVER_PORT=18080 QUEUE_ADMISSION_RATE_PER_SECOND=300 ./gradlew bootRun
```

```bash
BASE_URL=http://localhost:18080 \
EVENT_ID=loadtest-005-entry-10000-1s-20260511223637 \
RATE=10000 \
DURATION=1s \
PRE_ALLOCATED_VUS=10000 \
MAX_VUS=12000 \
k6 run \
  --summary-export docs/load-test-results/005-queue-entry-10000-1s-summary.json \
  k6-load-test/queue-entry-rate.js
```

## k6 설정 확인

`k6 inspect` 기준:

```text
executor: constant-arrival-rate
rate: 10000
timeUnit: 1s
duration: 1s
preAllocatedVUs: 10000
maxVUs: 12000
thresholds:
  http_req_failed: rate < 0.05
  http_req_duration: p95 < 2000ms
```

## k6 결과 요약

```text
iterations: 10012
http_reqs: 10012
http request rate: 4521.02/s

checks: 15256 passed / 4768 failed
check success rate: 76.18%

queue entries: 7628
invalid entry responses: 2384
```

HTTP 지표:

```text
http_req_failed: 23.81% (2384 failed / 10012 total)
http_req_duration:
  avg: 722.18ms
  med: 912.14ms
  p90: 1.15s
  p95: 1.18s
  max: 1.24s

http_req_blocked:
  avg: 15.08ms
  p95: 77.52ms

http_req_connecting:
  avg: 13.64ms
  p95: 75.55ms

http_req_waiting:
  avg: 721.18ms
  p95: 1.18s
```

대표 에러:

```text
connection reset by peer
read: connection reset by peer
```

## Redis 상태 점검

```bash
docker exec ticketing-system-redis-1 redis-cli SCARD queue-events
docker exec ticketing-system-redis-1 redis-cli SISMEMBER queue-events loadtest-005-entry-10000-1s-20260511223637
docker exec ticketing-system-redis-1 redis-cli ZCARD waiting:loadtest-005-entry-10000-1s-20260511223637
docker exec ticketing-system-redis-1 redis-cli ZCARD active-users:loadtest-005-entry-10000-1s-20260511223637
docker exec ticketing-system-redis-1 redis-cli INFO memory
```

결과:

```text
queue-events count: 4
event registered: 1
waiting count for event: 2228
active-users count for event: 5400
Redis used_memory_human: 11.68M
Redis used_memory_peak_human: 11.70M
```

## 이전 실험과 비교

| 항목 | 10,000명 full flow | 1초 10,000 entry-only |
|------|--------------------|------------------------|
| executor | shared-iterations | constant-arrival-rate |
| polling 포함 | 예 | 아니오 |
| iterations | 10,000 | 10,012 |
| http_reqs | 11,434 | 10,012 |
| 실패율 | 80.39% | 23.81% |
| p95 latency | 665.47ms | 1.18s |
| queue entries | 808 | 7,628 |
| 대표 에러 | connection reset by peer | connection reset by peer |

entry-only로 분리하고 arrival rate를 제어하자 성공 진입 수는 808건에서 7,628건으로 크게 늘었다. 다만 1초 동안 10,000건을 단일 로컬 WAS에 넣는 조건에서는 여전히 connection reset이 발생했고, 실패율 목표인 5% 미만에는 도달하지 못했다.

## 해석

이번 실험은 polling이 주요 병목이 아니라는 점을 분리해서 확인하는 데 의미가 있다. `POST /queue`만 보냈을 때도 23.81% 실패가 발생했으므로, 병목은 queue status polling보다 요청 수용 계층에 더 가깝다.

다만 full flow 테스트보다 결과가 훨씬 좋아졌기 때문에, 테스트 모델을 entry-only와 full-flow로 분리하는 접근은 유효하다.

## 결론

- 1초 10,000건 entry-only 테스트도 threshold 기준으로는 실패했다.
- p95 latency는 1.18s로 통과했다.
- 10,012건 중 7,628건이 queue token을 받았다.
- 실패는 대부분 connection reset 계열이다.
- 다음 실험은 같은 entry-only 조건에서 `RATE=3000`, `RATE=5000`, `RATE=7000`처럼 단계적으로 올려 로컬 환경의 안정 구간을 찾는 것이 좋다.
