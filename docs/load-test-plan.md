# Load Test Plan

이 문서는 명절 기차표 예매 상황을 로컬 Mac에서 재현하기 위한 부하 테스트 계획을 정의합니다.

테스트의 목적은 로컬 환경에서 실제 상용 처리량을 증명하는 것이 아니라, 대기열 기반 트래픽 제어가 초과 예매와 중복 예매를 막으면서 예매 트랜잭션 부하를 제한하는지 검증하는 것입니다.

## 목표 시나리오

- 동시 진입 사용자: 30,000명
- 사용자는 예매 시작 시각에 동시에 진입 요청
- 사용자는 대기 상태를 5초 간격으로 polling
- active 상태가 되거나 token이 만료되면 queue-only 테스트 사용자는 종료
- 좌석 예매, idempotency, MySQL 영속화는 이번 부하 테스트에서 실행하지 않음

## 로컬 머신 기준

현재 기준 로컬 환경:

```text
CPU: Apple M1 Pro, 10 cores
Memory: 32GB
OS: macOS
```

Spring Boot, Redis, MySQL, 부하 발생기를 같은 Mac에서 실행하면 모든 구성 요소가 동일한 CPU와 메모리를 공유합니다.

따라서 30,000 virtual users 테스트 결과는 실제 운영 환경의 처리량이 아니라, 로컬 환경에서 재현 가능한 병목 관찰 결과로 해석합니다.

## 트래픽 모델

30,000명이 동시에 진입하더라도 polling interval에 따라 실제 RPS는 달라집니다.

```text
30,000 users / 5s polling = 약 6,000 RPS
30,000 users / 3s polling = 약 10,000 RPS
30,000 users / 1s polling = 약 30,000 RPS
```

이 프로젝트의 기본 polling interval은 5초입니다.

## Admission Rate

Admission Scheduler는 대기열에서 초당 N명만 예매 가능 상태로 전환합니다.

이번 프로젝트에서 검증할 admission rate:

```text
300 users/s: 30,000명 queue-only 목표 테스트 기준
```

admission rate가 300 users/s라면 30,000명의 사용자를 모두 active admission으로 전환하는 데 이론상 약 100초가 걸립니다. 실제 로컬 테스트에서는 polling interval, Redis 처리량, JVM/CPU 상태에 따라 관찰 시간이 달라질 수 있습니다.

## 테스트 시나리오

### 30,000명 Queue-Only Test

목표:

- 30,000명 동시 진입 시나리오를 virtual users로 재현
- 대기열 등록, 상태 polling, active admission 전환이 Redis 기반으로 유지되는지 확인
- 로컬 병목 지점을 관찰

설정:

```text
virtual users: 30,000
polling interval: 5s
admission rate: 300 users/s
```

성공 기준:

- API 서버가 완전히 응답 불능 상태로 빠지지 않는다.
- Queue API는 대기 상태를 계속 반환한다.
- `POST /queue`는 30,000명의 사용자에게 queue token을 반환한다.
- `GET /queue/{queueToken}`은 `WAITING`, `ENTERED`, `EXPIRED` 중 하나의 유효한 상태를 반환한다.
- 대기열 등록과 상태 조회는 MySQL을 호출하지 않는다.

실행:

```bash
docker compose up -d redis mysql
QUEUE_ADMISSION_RATE_PER_SECOND=300 ./gradlew bootRun
BASE_URL=http://localhost:8080 k6 run k6-load-test/queue-admission.js
```

30,000 VU queue-only 테스트는 대기열 계층만 압박한다. 이 테스트는 Redis `waiting:{eventId}`, `queue-token:{token}`, `queue-user-token:{eventId}:{userId}`, `queue-events`, `active:{eventId}:{userId}` 흐름을 관찰하기 위한 것이며, 좌석 선점과 MySQL 저장 성능을 주장하는 근거로 사용하지 않는다.

## 사용자 흐름

부하 테스트 사용자는 다음 순서로 행동합니다.

```text
1. POST /api/events/{eventId}/queue
2. queue token 수신
3. GET /api/events/{eventId}/queue/{queueToken} polling
4. WAITING이면 5초 후 다시 polling
5. ENTERED 또는 EXPIRED이면 결과 기록 후 종료
```

## 수집 지표

반드시 수집할 지표:

- total queue registrations
- active token issued count
- Queue API p50, p95, p99 latency
- Redis memory usage
- application CPU and memory usage
- queue entry request count
- queue status polling request count
- `WAITING`, `ENTERED`, `EXPIRED` 응답 수
- current waiting count
- current active count

로컬 머신 조건 기록 항목:

```text
CPU:
Memory:
OS:
Java version:
Docker version:
Redis image:
MySQL image:
Spring profile:
queue.admission-rate-per-second:
queue.poll-after-seconds:
queue.active-ttl-seconds:
k6 version:
```

Admission rate 검증 절차:

```text
1. admission rate를 300 users/s로 설정한다.
2. 30,000명의 사용자를 queue-only 테스트로 진입시킨다.
3. Queue API가 polling 응답을 유지하는지 확인한다.
4. active token 발급이 설정 rate를 기준으로 진행되는지 application log와 Redis 지표로 확인한다.
5. 테스트 결과는 로컬 머신 조건과 함께 기록한다.
```

## 정합성 검증

30,000명 queue-only 테스트는 Reservation API와 MySQL 저장을 검증하지 않는다. 테스트 후에는 Redis 대기열 key와 k6 결과를 기준으로 검증한다.

```bash
# event registry에 테스트 event가 등록되어야 한다.
redis-cli SCARD queue-events

# 테스트 event의 waiting queue가 처리되었는지 확인한다.
redis-cli ZCARD waiting:{eventId}

# 관찰용 active user 수를 확인한다.
redis-cli ZCARD active-users:{eventId}
```

기대 결과:

```text
queue entry failures within threshold
queue status failures within threshold
waiting queue eventually drains according to admission rate
Queue API p95 latency within recorded local target
```

## 결과 기록 포맷

이번 30,000명 queue-only 실행 결과:

- [005-queue-admission-30000.md](load-test-results/005-queue-admission-30000.md)

추가 비교 실행 결과:

- [005-queue-admission-10000.md](load-test-results/005-queue-admission-10000.md)
- [005-queue-entry-10000-1s.md](load-test-results/005-queue-entry-10000-1s.md)
- [005-fast-queue-entry-10000-1s.md](load-test-results/005-fast-queue-entry-10000-1s.md)
- [005-fast-queue-entry-30000-1s.md](load-test-results/005-fast-queue-entry-30000-1s.md)

각 테스트 결과는 다음 형식으로 기록합니다.

```text
Test name:
Date:
Machine:
Application settings:
Redis settings:
MySQL settings:
Virtual users:
Polling interval:
Admission rate:

Result:
- total requests:
- queue registrations:
- active tokens:
- waiting responses:
- entered responses:
- expired responses:
- p95 queue latency:
- CPU max:
- memory max:

Findings:
- bottleneck:
- next change:
```

## 해석 규칙

- 로컬 테스트 결과를 운영 환경 성능으로 표현하지 않는다.
- 실패한 테스트도 병목을 찾은 결과로 기록한다.
- admission rate와 polling interval을 변경할 때마다 결과를 따로 기록한다.
- 성능 개선 전후에는 같은 조건으로 재측정한다.
