# 로컬에서 30,000명 대기열 진입을 밀어 넣어 보기

## 문제 정의

명절 기차표 예매처럼 특정 시각에 사용자가 한꺼번에 몰리면, 모든 요청을 바로 예매 트랜잭션으로 보내는 구조는 위험하다. 좌석 선점과 DB 저장은 정합성이 중요한 영역이기 때문에, 그 앞단에서 트래픽을 흡수하고 시스템이 감당 가능한 만큼만 흘려보내야 한다.

이번 실험의 질문은 단순했다.

```text
로컬 환경에서 30,000명의 사용자가 동시에 대기열에 진입하면 Queue API와 Redis 대기열은 어디까지 버틸까?
```

## 설계 요약

이번 테스트는 queue-only로 제한했다. 즉 좌석 예매 API, idempotency 처리, MySQL 영속화 worker는 부하 대상에서 제외했다.

테스트 사용자는 다음 흐름만 수행한다.

```text
1. POST /api/events/{eventId}/queue
2. queue token 수신
3. GET /api/events/{eventId}/queue/{queueToken} polling
4. WAITING이면 다시 polling
5. ENTERED 또는 EXPIRED면 종료
```

대기열은 Redis Sorted Set을 사용한다.

```text
waiting:{eventId}
queue-token:{token}
queue-user-token:{eventId}:{userId}
queue-events
active:{eventId}:{userId}
active-users:{eventId}
```

Admission Scheduler는 초당 300명씩 waiting user를 active admission으로 전환한다.

## 테스트 조건

```text
Machine: Apple M1 Pro, 32GB memory
OS: macOS 15.4.1
Java: 21.0.8
Docker: 28.5.1
k6: 1.6.1
Redis: redis:7.4-alpine
MySQL: mysql:8.0
Application port: 18080
Virtual users: 30,000
Iterations: 30,000
Admission rate: 300 users/s
Polling interval: 5s
```

실행 명령:

```bash
BASE_URL=http://localhost:18080 \
EVENT_ID=loadtest-005-30000-20260511221213 \
MAX_POLLS=30 \
k6 run \
  --summary-export docs/load-test-results/005-queue-admission-30000-summary.json \
  k6-load-test/queue-admission.js
```

## 결과

결과부터 말하면, 테스트는 실패했다. 하지만 좋은 실패였다.

```text
iterations: 30,000 완료
http_reqs: 44,590
http_req_failed: 48.90%
http_req_duration p95: 10.46s
queue entries: 8,192
WAITING responses: 6,398
ENTERED responses: 8,192
```

대표 에러는 다음과 같았다.

```text
connection reset by peer
can't assign requested address
i/o timeout
request timeout
```

Redis 상태를 보면 테스트 event는 `queue-events`에 등록됐고, 해당 event의 waiting queue는 0으로 비었다. 성공적으로 queue에 들어간 사용자들은 active admission까지 전환됐다.

## 해석

이번 결과에서 가장 중요한 부분은 실패율 자체보다 실패의 모양이다.

에러가 `SEAT_ALREADY_TAKEN`이나 Redis command timeout 같은 애플리케이션 도메인 문제가 아니라, 연결 생성과 수용 단계에서 발생했다. k6가 로컬에서 30,000 VU를 한 번에 열면서 `can't assign requested address`, `connection reset by peer`, `i/o timeout`이 쏟아졌다.

즉 이번 병목은 Redis Sorted Set 대기열이라기보다, 단일 로컬 머신에서 부하 발생기와 애플리케이션을 동시에 돌릴 때의 socket/port/Tomcat accept 계층으로 보는 것이 맞다.

이건 꽤 현실적인 교훈이다. 부하 테스트가 실패했다고 해서 곧바로 비즈니스 로직이 실패했다고 결론내리면 안 된다. 먼저 실패가 어느 계층에서 발생했는지 봐야 한다.

## 한계

- 로컬 머신 한 대에서 k6, Spring Boot, Redis, MySQL을 모두 실행했다.
- 30,000 VU를 ramping 없이 한 번에 초기화했다.
- macOS file descriptor, ephemeral port 범위, Tomcat connection 설정을 별도로 튜닝하지 않았다.
- 이번 테스트는 queue-only다. 좌석 선점과 MySQL 영속화 성능은 검증하지 않았다.

## 다음 단계

다음 실험은 성공률을 높이기 위해 다음 순서로 진행하는 것이 좋아 보인다.

1. k6 부하를 ramping 방식으로 바꿔 같은 30,000명 총량을 더 현실적으로 보낸다.
2. macOS file descriptor와 ephemeral port 설정을 기록한다.
3. Tomcat max connections, accept count, thread pool 설정을 명시적으로 조정한다.
4. 부하 발생기를 애플리케이션과 분리한다.
5. Redis key를 event별로 더 엄격하게 정리하고 관찰한다.

## 추가 실험: 10,000명으로 낮추면 나아질까?

30,000명 테스트 이후 같은 방식으로 10,000명 queue-only 테스트도 실행했다. 결과는 직관과 조금 달랐다.

```text
iterations: 10,000
http_req_failed: 80.39%
http_req_duration p95: 665.47ms
queue entries: 808
대표 에러: connection reset by peer
```

10,000명은 p95 latency는 좋아졌지만 실패율은 오히려 더 높았다. 이유는 전체 실행 시간이 매우 짧아졌고, 초반에 동시에 열린 연결이 reset되면서 대부분의 실패가 한 번에 발생했기 때문이다.

이 결과는 “숫자를 줄이면 해결된다”보다 “동시 시작 방식이 문제다”에 더 가깝다. 다음 실험은 10,000명 또는 30,000명을 유지하되, 한 번에 여는 방식 대신 ramping 방식으로 바꾸는 편이 더 의미 있다.

## 추가 실험: 1초 동안 10,000건 entry-only 요청

다음으로 polling을 제거하고 `POST /queue`만 1초 동안 10,000건 보내는 실험을 했다. 이 실험은 `shared-iterations`가 아니라 `constant-arrival-rate`를 사용했다.

```text
executor: constant-arrival-rate
rate: 10,000/s
duration: 1s
http_req_failed: 23.81%
http_req_duration p95: 1.18s
queue entries: 7,628
대표 에러: connection reset by peer
```

결과는 이전 full flow보다 훨씬 좋아졌다. 10,000명 full flow에서는 성공 진입이 808건이었지만, entry-only에서는 7,628건까지 올라갔다. 다만 여전히 실패율 5% 기준은 넘지 못했다.

이 실험으로 최소한 두 가지는 분리해서 볼 수 있게 됐다. 첫째, polling을 제거하면 성공률은 크게 올라간다. 둘째, `POST /queue`만 보더라도 1초 10,000건은 현재 로컬 단일 WAS 설정에서 안정 구간을 넘어선다.

## 최종 개선: hot path와 Tomcat 수용 계층을 함께 정리

마지막으로 `POST /queue`의 hot path를 줄였다. 첫 응답에서 `rank`, `totalWaiting` 계산을 제거하고, Redis 등록은 Lua script로 원자화했다. 동일 `event/user` 중복 진입은 동시에 100개 요청이 들어와도 하나의 token과 하나의 waiting member로 수렴하도록 테스트했다.

여기서 한 번 더 배운 점이 있었다. hot path만 줄였을 때는 10,000/s 테스트가 바로 성공하지 않았다. 순간 connection reset이 계속 발생했기 때문이다. 그래서 embedded Tomcat 설정을 명시했다.

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

최종 결과:

```text
executor: constant-arrival-rate
rate: 10,000/s
duration: 1s
http_req_failed: 0.00%
http_req_duration p95: 825.15ms
queue entries: 10,068
```

이 결과는 “Redis를 썼더니 빨라졌다” 같은 단순한 결론이 아니다. 대기열 진입 API의 hot path를 줄이고, 중복 진입을 원자적으로 처리하고, WAS connection/thread/backlog 설정까지 맞춰야 burst traffic을 받아낼 수 있다는 결론에 가깝다.

로그도 같은 맥락에서 정리했다. 요청마다 `INFO` 로그를 찍는 대신 k6 summary JSON, Redis cardinality, 동시성 테스트, 결과 문서를 남겼다. 1초 10,000 요청 조건에서 request log는 관측성이 아니라 병목이 되기 쉽기 때문이다.

## 30,000/s로 올리면 어떻게 될까?

같은 fast queue entry 구현에서 rate만 30,000/s로 올린 실험도 했다. 결과는 실패였다.

```text
executor: constant-arrival-rate
rate: 30,000/s
duration: 1s
http_req_failed: 72.61%
http_req_duration p95: 10.62s
queue entries: 6,192
대표 에러: can't assign requested address, EOF
```

이 실패는 꽤 중요한 데이터다. 대표 에러가 `can't assign requested address`였기 때문에, 애플리케이션 코드가 500을 반환한 상황이라기보다 k6가 로컬에서 순간적으로 너무 많은 outbound TCP connection을 만들다가 OS socket/ephemeral port 한계에 먼저 닿은 것으로 보는 편이 타당하다.

즉 이 프로젝트에서 “검증된 로컬 단일 머신 기준선”은 1초 10,000건 queue entry다. 30,000/s를 계속 검증하려면 부하 발생기를 분리하거나, ramping 방식으로 유입을 나누고, OS TCP 설정과 Redis 배치까지 함께 다뤄야 한다.

## 결론

이번 실험은 “30,000명 queue-only 테스트 성공”이 아니라 “현재 로컬 기본 설정에서는 연결 계층이 먼저 병목이 된다”는 기준선을 얻은 실험이다.

이 기준선이 있어야 다음 개선이 의미를 가진다. 이제부터는 Redis 대기열 설계 자체를 의심하기 전에, 로컬 부하 발생 방식과 서버 연결 수용 설정을 먼저 정리하는 것이 맞다.
