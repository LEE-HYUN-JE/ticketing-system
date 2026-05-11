# 10,000명 Queue-Only 부하 테스트 결과

## 실행 개요

- 테스트 이름: 10,000명 Queue-Only Queue Admission
- 작성일: 2026-05-11
- 실행 시각: 2026-05-11 22:21:30 KST
- 상태: FAILED
- 이유: `http_req_duration p95`는 665.47ms로 통과했지만, `http_req_failed`가 80.39%로 목표를 초과했다.
- 테스트 event id: `loadtest-005-10000-20260511222130`
- 대상 URL: `http://localhost:18080`

## 실행 명령

```bash
k6 inspect -e VUS=10000 -e ITERATIONS=10000 k6-load-test/queue-admission.js
docker compose up -d redis mysql
SERVER_PORT=18080 QUEUE_ADMISSION_RATE_PER_SECOND=300 ./gradlew bootRun
```

```bash
BASE_URL=http://localhost:18080 \
EVENT_ID=loadtest-005-10000-20260511222130 \
MAX_POLLS=20 \
k6 run \
  -e VUS=10000 \
  -e ITERATIONS=10000 \
  --summary-export docs/load-test-results/005-queue-admission-10000-summary.json \
  k6-load-test/queue-admission.js
```

## k6 설정 확인

`k6 inspect -e VUS=10000 -e ITERATIONS=10000 k6-load-test/queue-admission.js` 기준:

```text
executor: shared-iterations
vus: 10000
iterations: 10000
maxDuration: 10m
thresholds:
  http_req_failed: rate < 0.05
  http_req_duration: p95 < 2000ms
```

## k6 결과 요약

```text
iterations: 10000
http_reqs: 11434
iteration rate: 1395.02/s
http request rate: 1595.07/s

checks: 4484 passed / 18384 failed
check success rate: 19.61%

queue entries: 808
WAITING responses: 626
ENTERED responses: 808
EXPIRED responses: 0
invalid status responses: 0
```

HTTP 지표:

```text
http_req_failed: 80.39% (9192 failed / 11434 total)
http_req_duration:
  avg: 213.47ms
  med: 155.70ms
  p90: 570.33ms
  p95: 665.47ms
  max: 1028.40ms

http_req_blocked:
  avg: 464.18ms
  p95: 1064.12ms

http_req_connecting:
  avg: 463.91ms
  p95: 1064.10ms

http_req_waiting:
  avg: 211.24ms
  p95: 663.63ms
```

대표 에러:

```text
connection reset by peer
read: connection reset by peer
```

## Redis 상태 점검

```bash
docker exec ticketing-system-redis-1 redis-cli SCARD queue-events
docker exec ticketing-system-redis-1 redis-cli SISMEMBER queue-events loadtest-005-10000-20260511222130
docker exec ticketing-system-redis-1 redis-cli ZCARD waiting:loadtest-005-10000-20260511222130
docker exec ticketing-system-redis-1 redis-cli ZCARD active-users:loadtest-005-10000-20260511222130
docker exec ticketing-system-redis-1 redis-cli INFO memory
```

결과:

```text
queue-events count: 3
event registered: 1
waiting count for event: 0
active-users count for event: 810
Redis used_memory_human: 6.71M
Redis used_memory_peak_human: 7.09M
```

## 30,000명 테스트와 비교

| 항목 | 30,000명 | 10,000명 |
|------|----------|----------|
| iterations | 30,000 | 10,000 |
| http_reqs | 44,590 | 11,434 |
| 실패율 | 48.90% | 80.39% |
| p95 latency | 10.46s | 665.47ms |
| queue entries | 8,192 | 808 |
| 대표 에러 | connection reset, timeout, address 할당 실패 | connection reset |

10,000명 테스트는 latency 자체는 크게 개선됐지만 성공 진입 수는 808건에 그쳤다. 실패율이 더 높아진 이유는 30,000명 테스트보다 전체 실행 시간이 훨씬 짧아, 초반 connection reset이 대부분의 요청을 차지했기 때문으로 보인다.

## 해석

10,000명으로 낮춰도 “동시에 한 번에 연결을 여는 방식”은 여전히 로컬 환경에서 불안정했다. 다만 30,000명 테스트와 달리 `can't assign requested address`나 긴 timeout보다 `connection reset by peer`가 중심이었다.

즉 30,000명에서는 클라이언트/OS 연결 자원 고갈까지 번졌고, 10,000명에서는 서버 수용 계층 또는 Tomcat connection/backlog 한계가 먼저 드러난 것으로 해석할 수 있다.

## 결론

- 10,000명 테스트도 threshold 기준으로는 실패했다.
- p95 latency는 통과했지만 실패율이 80.39%로 매우 높다.
- 동시 시작 방식 자체가 병목을 만든다.
- 다음 실험은 VU 수를 더 낮추기보다 ramping arrival/ramping VU 방식으로 바꾸는 것이 더 의미 있다.

## 다음 실험 제안

1. `ramping-vus` 또는 `ramping-arrival-rate`로 10,000명을 점진적으로 진입시킨다.
2. Tomcat `server.tomcat.threads.max`, `server.tomcat.accept-count`, `server.tomcat.max-connections`를 명시적으로 설정한다.
3. macOS file descriptor limit과 ephemeral port 범위를 테스트 문서에 기록한다.
4. 부하 발생기를 애플리케이션과 분리한다.
