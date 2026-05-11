# Load Test Plan

이 문서는 명절 기차표 예매 상황을 로컬 Mac에서 재현하기 위한 부하 테스트 계획을 정의합니다.

테스트의 목적은 로컬 환경에서 실제 상용 처리량을 증명하는 것이 아니라, 대기열 기반 트래픽 제어가 초과 예매와 중복 예매를 막으면서 예매 트랜잭션 부하를 제한하는지 검증하는 것입니다.

## 목표 시나리오

- 동시 진입 사용자: 30,000명
- 좌석 수: 2,000석
- 사용자는 예매 시작 시각에 동시에 진입 요청
- 사용자는 대기 상태를 5초 간격으로 polling
- active 상태가 된 사용자는 5초 동안 무작위 좌석을 선택한다고 가정
- 좌석 예매 요청은 idempotency key를 포함
- 성공 예매는 비동기로 MySQL에 저장

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

테스트할 admission rate:

```text
100 users/s: 안정성 확인
300 users/s: 기본 목표
500 users/s: 도전 목표
1,000 users/s: 병목 확인
```

좌석이 2,000석이고 admission rate가 300 users/s라면, 모든 좌석 수만큼의 사용자가 입장하는 데 약 7초가 걸립니다. 사용자가 입장 후 5초 동안 좌석을 선택한다고 가정하면, 주요 예매 처리는 약 12초 전후에 집중됩니다.

## 테스트 단계

### Stage 1: Smoke Test

목표:

- 기능 흐름이 정상 동작하는지 확인
- 대기열 등록, active 전환, 좌석 선점, DB 저장까지 검증

설정:

```text
virtual users: 100
seats: 50
polling interval: 5s
admission rate: 20 users/s
```

성공 기준:

- 성공 예매 수는 50건을 초과하지 않는다.
- 중복 예매가 발생하지 않는다.
- 모든 성공 예매가 DB에 저장된다.

Queue-only smoke 실행:

```bash
docker compose up -d redis mysql
QUEUE_ADMISSION_RATE_PER_SECOND=20 ./gradlew bootRun
PRESET=smoke BASE_URL=http://localhost:8080 k6 run k6-load-test/queue-admission.js
```

Queue-only smoke는 Reservation API와 MySQL 저장까지 밀어 넣지 않고, 대기열 진입과 상태 polling만 검증한다. 이 단계의 성공 기준은 `POST /queue`가 100명의 사용자를 받아 queue token을 반환하고, `GET /queue/{queueToken}`이 `WAITING`, `ENTERED`, `EXPIRED` 중 하나의 유효한 상태를 계속 반환하는 것이다.

### Stage 2: Local Baseline

목표:

- 로컬 환경에서 안정적으로 반복 가능한 기준 성능을 측정

설정:

```text
virtual users: 3,000
seats: 500
polling interval: 5s
admission rate: 100 users/s
```

관찰 지표:

- Queue API p95 latency
- Reservation API p95 latency
- Redis command latency
- MySQL insert throughput
- worker lag

### Stage 3: High Load

목표:

- 10,000명 규모에서 대기열과 admission control이 동작하는지 확인

설정:

```text
virtual users: 10,000
seats: 2,000
polling interval: 5s
admission rate: 300 users/s
```

성공 기준:

- 성공 예매 수는 2,000건을 초과하지 않는다.
- 동일 사용자 중복 성공은 0건이다.
- active token 없는 예매 성공은 0건이다.
- DB 저장 건수는 Redis 성공 예매 건수와 최종적으로 일치한다.

### Stage 4: Target Scenario

목표:

- 30,000명 동시 진입 시나리오를 virtual users로 재현
- 로컬 병목 지점을 관찰

설정:

```text
virtual users: 30,000
seats: 2,000
polling interval: 5s
admission rate: 300 users/s
random seat selection delay: 5s
```

성공 기준:

- API 서버가 완전히 응답 불능 상태로 빠지지 않는다.
- Queue API는 대기 상태를 계속 반환한다.
- 성공 예매 수는 2,000건을 초과하지 않는다.
- 중복 예매는 0건이다.
- DB 저장은 지연될 수 있으나 최종적으로 Redis 성공 이벤트와 일치한다.

Queue-only target 실행:

```bash
docker compose up -d redis mysql
QUEUE_ADMISSION_RATE_PER_SECOND=300 ./gradlew bootRun
PRESET=queue_only_30000 BASE_URL=http://localhost:8080 k6 run k6-load-test/queue-admission.js
```

30,000 VU queue-only preset은 대기열 계층만 압박한다. 이 테스트는 Redis `waiting:{eventId}`, `queue-token:{token}`, `queue-user-token:{eventId}:{userId}`, `queue-events`, `active:{eventId}:{userId}` 흐름을 관찰하기 위한 것이며, 좌석 선점과 MySQL 저장 성능을 주장하는 근거로 사용하지 않는다.

## 사용자 흐름

부하 테스트 사용자는 다음 순서로 행동합니다.

```text
1. POST /api/events/{eventId}/queue
2. queue token 수신
3. GET /api/events/{eventId}/queue/{queueToken} polling
4. WAITING이면 5초 후 다시 polling
5. ENTERED이면 5초 동안 좌석 선택 대기
6. 무작위 seatId 생성
7. POST /api/events/{eventId}/reservations with Idempotency-Key
8. 결과 기록
```

## 수집 지표

반드시 수집할 지표:

- total queue registrations
- active token issued count
- reservation success count
- reservation failure count by reason
- duplicate reservation success count
- overbooking count
- Redis success event count
- MySQL reservation row count
- Queue API p50, p95, p99 latency
- Reservation API p50, p95, p99 latency
- worker lag
- Redis memory usage
- application CPU and memory usage

Queue-only preset에서 우선 수집할 지표:

- queue entry request count
- queue status polling request count
- `WAITING`, `ENTERED`, `EXPIRED` 응답 수
- active token issued count
- current waiting count
- current active count
- Queue API p50, p95, p99 latency
- Redis memory usage
- application CPU and memory usage

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
1. smoke에서는 admission rate를 20 users/s로 설정한다.
2. 100명의 사용자를 queue-only preset으로 진입시킨다.
3. active token 발급 속도가 순간적으로 크게 튀지 않고, 약 5초 안팎에서 전체 사용자가 ENTERED 또는 EXPIRED로 수렴하는지 확인한다.
4. target에서는 admission rate를 300 users/s로 설정한다.
5. 30,000명의 사용자를 queue-only preset으로 진입시킨다.
6. Queue API가 polling 응답을 유지하고, active token 발급이 설정 rate를 기준으로 진행되는지 application log와 Redis 지표로 확인한다.
```

## 정합성 검증 쿼리

테스트 후 검증해야 할 쿼리 또는 검증 로직:

```sql
-- 성공 예매 수가 좌석 수를 초과하지 않아야 한다.
select count(*) from reservations where event_id = ? and status = 'RESERVED';

-- 동일 좌석이 중복 예매되지 않아야 한다.
select seat_id, count(*)
from reservations
where event_id = ? and status = 'RESERVED'
group by seat_id
having count(*) > 1;

-- 동일 사용자가 중복 예매되지 않아야 한다.
select user_id, count(*)
from reservations
where event_id = ? and status = 'RESERVED'
group by user_id
having count(*) > 1;
```

기대 결과:

```text
reserved count <= 2,000
duplicated seats = 0
duplicated users = 0
```

## 결과 기록 포맷

각 테스트 결과는 다음 형식으로 기록합니다.

```text
Test name:
Date:
Machine:
Application settings:
Redis settings:
MySQL settings:
Virtual users:
Seats:
Polling interval:
Admission rate:

Result:
- total requests:
- queue registrations:
- active tokens:
- reservation success:
- reservation failure:
- DB rows:
- p95 queue latency:
- p95 reservation latency:
- CPU max:
- memory max:

Findings:
- bottleneck:
- next change:
```

## 해석 규칙

- 로컬 테스트 결과를 운영 환경 성능으로 표현하지 않는다.
- 실패한 테스트도 병목을 찾은 결과로 기록한다.
- admission rate, polling interval, worker batch size를 변경할 때마다 결과를 따로 기록한다.
- 성능 개선 전후에는 같은 조건으로 재측정한다.
