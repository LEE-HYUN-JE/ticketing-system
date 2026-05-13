# 대기열-예매 통합 부하 테스트 실행 가이드

## 목적

이 테스트는 단순히 대기열 토큰을 발급받는 것에서 끝나지 않고, 사용자가 실제로 예매까지 완료하는지를 검증한다.

검증 대상은 다음과 같다.

- 사용자가 대기열에 진입하고 queue token을 받는가
- queue token으로 대기 순번을 polling할 수 있는가
- active 상태가 된 사용자만 예매를 시도하는가
- 사용자가 2초 동안 랜덤 좌석을 재시도해 좌석을 선점하는가
- 최종 예약 수가 좌석 수를 넘지 않는가
- 좌석 중복 예약이 없는가
- 사용자 중복 예약이 없는가
- Redis 예약 상태와 MySQL 최종 반영 상태가 일치하는가

## k6 실행

현재 기본 좌석 수는 `application.yml` 기준 `2000`개다.

```bash
EVENT_ID=reservation-flow-10000-$(date +%Y%m%d%H%M%S)
SUMMARY_PATH=/tmp/${EVENT_ID}-summary.json

docker run --rm --network ticketing-system_default \
  -v "$PWD/k6-load-test:/scripts:ro" \
  -v /tmp:/out \
  -e EVENT_ID="$EVENT_ID" \
  -e BASE_URL=http://load-balancer \
  -e RATE=10000 \
  -e DURATION=1s \
  -e GRACEFUL_STOP=90s \
  -e PRE_ALLOCATED_VUS=10000 \
  -e MAX_VUS=12000 \
  -e SEAT_CAPACITY=2000 \
  -e MAX_POLLS=220 \
  -e POLL_INTERVAL_SECONDS=0.2 \
  -e RESERVATION_WINDOW_SECONDS=2 \
  -e MONITOR_LOG=sampled \
  -e MONITOR_LOG_SAMPLE_RATE=100 \
  grafana/k6 run --summary-export /out/${EVENT_ID}-summary.json /scripts/queue-reservation-flow.js
```

전체 사용자 로그를 보고 싶다면 `MONITOR_LOG=all`로 실행한다. 1만 명 테스트에서는 로그가 매우 많아지므로 기본값은 sampled를 권장한다.

## 모니터링 로그 예시

```text
[event=reservation-flow-10000] user-100 의 대기순번은 9821 입니다. totalWaiting=10000
[event=reservation-flow-10000] user-100 이 Active 상태로 진입했습니다. 예매 시작 .. activeExpiresInSeconds=59
[event=reservation-flow-10000] user-100 이 seat-153 좌석을 예매했습니다.
[event=reservation-flow-10000] user-873 예매 마감으로 실패되었습니다. 2초 안에 좌석을 선점하지 못했습니다.
```

## 결과 검증

k6가 종료된 뒤 Redis Stream worker가 MySQL에 반영할 시간을 몇 초 준 다음 검증한다.

```bash
sleep 5

EVENT_ID="$EVENT_ID" \
K6_SUMMARY_PATH="$SUMMARY_PATH" \
SEAT_CAPACITY=2000 \
bash scripts/verify-reservation-flow.sh
```

정상 기대값은 다음과 같다.

```text
예매 실패된 총 인원: 8000 명
예매 성공한 총 인원: 2000 명
좌석 중복 입석: 0 개
사용자 중복 예매: 0 개
좌석 수 초과 예약: 0 건
```

단, 랜덤 좌석 재시도 방식은 충돌이 많으면 2초 안에 2000석을 모두 채우지 못할 수 있다. 이 경우 초과 예약 방지 검증은 여전히 유효하지만, "정확히 2000석 완판"을 검증하려면 `RESERVATION_WINDOW_SECONDS`를 늘리거나 deterministic 좌석 배정 시나리오를 별도로 사용해야 한다.

## 실제 실행 결과

- 2026-05-13 실행 결과: [reservation-flow-10000-20260513.md](load-test-results/reservation-flow-10000-20260513.md)
