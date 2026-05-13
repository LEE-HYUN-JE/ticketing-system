# 대기열-예매 통합 부하 테스트 결과

## 테스트 조건

- 실행 일시: 2026-05-13
- eventId: `reservation-flow-10000-graceful-20260513114940`
- 테스트 경로: `k6 container -> Docker network -> Nginx load-balancer -> Queue WAS/Reservation WAS -> Redis -> Redis Stream Worker -> MySQL`
- 대기열 유입 목표: 10,000명, 1초
- 실제 대기열 진입 성공: 10,013명
- 좌석 수: 2,000석
- 좌석 선택 방식: active 진입 후 2초 동안 `seat-1` ~ `seat-2000` 랜덤 재시도
- Admission 설정: 초당 300명 active 전환
- k6 `GRACEFUL_STOP`: 90초

## k6 결과

| 지표 | 값 |
| --- | ---: |
| 완료 iteration | 10,013 |
| dropped iteration | 204 |
| 대기열 진입 성공 | 10,013 |
| active 진입 확인 | 10,013 |
| queue status invalid | 1 |
| 예매 시도 | 123,735 |
| 좌석 선점 충돌 | 121,735 |
| 예매 성공 응답 | 2,000 |
| 예매 마감/타임아웃 | 8,013 |
| HTTP 요청 수 | 388,321 |
| HTTP 실패 수 | 1 |
| HTTP 실패율 | 0.0002575% |
| HTTP p95 | 802.76ms |
| HTTP max | 6.52s |

## 리소스 사용량 추가 측정

최초 최종 run(`reservation-flow-10000-graceful-20260513114940`)에서는 컨테이너별 CPU/메모리 사용량을 수집하지 않았다. 따라서 같은 10,000명 목표 시나리오를 리소스 계측용으로 한 번 더 실행하고, `docker stats` streaming 결과에서 컨테이너별 최대 CPU 사용률과 최대 메모리 사용량을 계산했다.

- 리소스 측정 eventId: `reservation-flow-10000-resource2-20260513124043`
- 측정 방식: `docker stats --format '{{json .}}'` streaming
- 샘플 수: 컨테이너당 242개
- 측정 대상: Load Balancer, Queue WAS 3대, Reservation WAS 1대, Reservation Worker, Redis, MySQL
- 제외 대상: `queue-scheduler`
- 리소스 측정 run의 실제 대기열 진입 성공: 9,890명
- 리소스 측정 run의 최종 예약: 2,000건
- 좌석 중복/사용자 중복/좌석 수 초과: 모두 0건

CPU 사용률은 Docker 기준 값이므로 100%를 초과할 수 있다. 예를 들어 300%는 대략 CPU core 3개 분량의 시간을 사용했다는 의미다.

| 컴포넌트 | 최대 CPU | 최대 메모리 사용량 | 최대 메모리 비율 |
| --- | ---: | ---: | ---: |
| Load Balancer (nginx) | 186.77% | 388.6MiB | 2.47% |
| Queue WAS 1 | 188.58% | 1.505GiB | 9.80% |
| Queue WAS 2 | 224.61% | 1.489GiB | 9.70% |
| Queue WAS 3 | 261.06% | 1.495GiB | 9.74% |
| Reservation WAS 1 | 317.44% | 1.371GiB | 8.93% |
| Reservation Worker | 49.65% | 764.8MiB | 4.86% |
| Redis | 79.70% | 151.5MiB | 0.96% |
| MySQL | 31.97% | 480.8MiB | 3.06% |

해석하면, 이번 로컬 테스트에서는 Redis/MySQL보다 WAS 계층과 Load Balancer 쪽 CPU 사용량이 더 크게 나타났다. 이는 좌석 선점과 대기열 상태 확인이 Redis에서 빠르게 끝나고, HTTP 요청 수(대기열 진입 + polling + 예매 재시도)가 WAS/nginx를 반복적으로 통과하기 때문이다. MySQL은 Redis Stream worker를 통해 성공한 2,000건만 비동기로 저장하므로 hot path의 순간 부하에서 분리되어 있다.

단, 이 값은 `docker stats` 기반 컨테이너 관측치다. JVM heap/GC pause, Redis command latency, MySQL slow query 같은 내부 지표까지 수집한 것은 아니므로 운영 수준의 병목 분석으로 보려면 Actuator metrics, Redis INFO/LATENCY, MySQL performance schema, Prometheus/Grafana를 추가해야 한다.

## 최종 정합성 검증

검증은 `scripts/verify-reservation-flow.sh`로 수행했다. `K6_SUMMARY_PATH`를 넘겨 실제 k6 결과의 `queue_entries`를 총 요청 수로 사용했다.

검증 스크립트는 k6 summary, Redis, MySQL을 각각 다음 기준으로 조회한다.

| 확인 항목 | 조회 대상 | 발췌 쿼리/명령 | 확인 의미 |
| --- | --- | --- | --- |
| 총 요청 수 | k6 summary JSON | `.metrics.queue_entries.count` | 실제 대기열 진입 성공 수를 총 요청 수로 사용한다. k6가 목표 rate를 모두 만들지 못한 경우에도 실제 성공 요청 기준으로 실패 인원을 계산한다. |
| 완료/드롭 iteration | k6 summary JSON | `.metrics.iterations.count`, `.metrics.dropped_iterations.count` | k6가 실제 완료한 사용자 흐름과 생성하지 못한 iteration 수를 확인한다. |
| 예매 시도/성공/실패 유형 | k6 summary JSON | `.metrics.reservation_attempts.count`, `.metrics.reservation_success.count`, `.metrics.reservation_closed.count`, `.metrics.reservation_seat_taken.count` | active 이후 사용자가 좌석을 얼마나 재시도했고, 성공/마감/좌석 충돌이 어떻게 분포했는지 확인한다. |
| Redis 예약 좌석 수 | Redis keyspace | `redis-cli --scan --pattern "seat:${EVENT_ID}:*" \| wc -l` | Redis에서 실제 선점된 좌석 key 수를 확인한다. |
| Redis 예약 사용자 수 | Redis keyspace | `redis-cli --scan --pattern "reservation:user:${EVENT_ID}:*" \| wc -l` | Redis에서 예약 성공으로 기록된 사용자 수를 확인한다. 좌석 수와 사용자 수가 다르면 Redis 내부 정합성이 깨진 것이다. |
| MySQL 최종 예약 수 | `reservations` table | `SELECT COUNT(*) FROM reservations WHERE event_id = ? AND status = 'RESERVED'` | Redis Stream worker가 MySQL에 최종 반영한 예약 row 수를 확인한다. |
| 좌석 중복 예매 | `reservations` table | `SELECT seat_id FROM reservations WHERE event_id = ? GROUP BY seat_id HAVING COUNT(*) > 1` | 같은 좌석이 둘 이상의 사용자에게 저장됐는지 확인한다. 정상값은 0건이다. |
| 사용자 중복 예매 | `reservations` table | `SELECT user_id FROM reservations WHERE event_id = ? GROUP BY user_id HAVING COUNT(*) > 1` | 같은 사용자가 둘 이상의 좌석을 예약했는지 확인한다. 정상값은 0건이다. |
| 좌석 수 초과 예약 | MySQL 예약 수와 `SEAT_CAPACITY` 비교 | `mysql_reserved_rows - SEAT_CAPACITY` | 최종 예약 수가 좌석 수 2,000개를 초과했는지 확인한다. 정상값은 0건이다. |

```text
예매 실패된 총 인원: 8013 명
예매 성공한 총 인원: 2000 명
좌석 중복 입석: 0 개
사용자 중복 예매: 0 개
좌석 수 초과 예약: 0 건

K6 완료 iteration 수: 10013
K6 dropped iteration 수: 204
K6 대기열 진입 성공 수: 10013
K6 대기열 진입 실패 수: 0
K6 예매 시도 수: 123735
K6 예매 성공 응답 수: 2000
K6 예매 마감/타임아웃 수: 8013
K6 NOT_ACTIVE 수: 0
K6 좌석 선점 충돌 수: 121735

Redis 예약 좌석 수: 2000
Redis 예약 사용자 수: 2000
MySQL RESERVED row 수: 2000
```

## 해석

이번 테스트의 핵심 검증은 통과했다.

- 1초 동안 약 1만 명이 대기열에 진입했다.
- 모든 대기열 진입 사용자가 active 상태까지 도달했다.
- active 사용자는 2초 동안 랜덤 좌석을 재시도했다.
- 좌석 선점 충돌이 121,735회 발생했지만, 최종 예약은 정확히 2,000건만 성공했다.
- Redis 기준 예약 좌석 수, Redis 기준 예약 사용자 수, MySQL 최종 저장 수가 모두 2,000으로 일치했다.
- 좌석 중복, 사용자 중복, 좌석 수 초과 예약은 모두 0건이었다.

HTTP 레벨에서는 queue status 요청 1건이 실패했다. 전체 388,321건 중 1건으로 비즈니스 정합성에는 영향을 주지 않았지만, 로컬 Docker 환경의 고부하 polling 중 일시적인 upstream close가 발생할 수 있음을 보여준다.

## 결론

현재 로컬 Docker 환경에서 대기열-예매 통합 플로우는 다음 조건을 만족했다.

- 대기열 진입: 성공
- 대기순번 polling: 거의 전부 성공, 1건 실패
- active 진입: 성공
- 랜덤 좌석 예매: 성공
- 2,000석 초과 예약 방지: 성공
- 좌석 중복 방지: 성공
- 사용자 중복 예매 방지: 성공
- Redis/MySQL 최종 정합성: 성공

따라서 이 테스트는 "초당 1만 명이 몰린 상황에서, 대기열을 통과한 사용자들이 랜덤 좌석 선점을 시도해도 좌석 수를 초과하지 않고 최종 2,000석만 예약된다"는 것을 검증한다.
