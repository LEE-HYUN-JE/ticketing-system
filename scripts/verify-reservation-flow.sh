#!/usr/bin/env bash
set -euo pipefail

EVENT_ID="${EVENT_ID:?EVENT_ID is required}"
TOTAL_REQUESTS="${TOTAL_REQUESTS:-}"
SEAT_CAPACITY="${SEAT_CAPACITY:-2000}"
MYSQL_SERVICE="${MYSQL_SERVICE:-mysql}"
REDIS_SERVICE="${REDIS_SERVICE:-redis}"
K6_SUMMARY_PATH="${K6_SUMMARY_PATH:-}"

metric_count() {
  local metric="$1"
  local default_value="${2:-0}"
  if [[ -n "${K6_SUMMARY_PATH}" && -f "${K6_SUMMARY_PATH}" ]] && command -v jq >/dev/null 2>&1; then
    jq -r --arg metric "$metric" '.metrics[$metric].count // 0' "${K6_SUMMARY_PATH}"
    return
  fi
  echo "${default_value}"
}

if [[ -z "${TOTAL_REQUESTS}" ]]; then
  if [[ -n "${K6_SUMMARY_PATH}" && -f "${K6_SUMMARY_PATH}" ]] && command -v jq >/dev/null 2>&1; then
    TOTAL_REQUESTS="$(metric_count queue_entries)"
    if (( TOTAL_REQUESTS == 0 )); then
      TOTAL_REQUESTS="$(metric_count iterations)"
    fi
  else
    echo "TOTAL_REQUESTS or K6_SUMMARY_PATH is required" >&2
    exit 1
  fi
fi

K6_ITERATIONS="$(metric_count iterations "${TOTAL_REQUESTS}")"
K6_DROPPED_ITERATIONS="$(metric_count dropped_iterations 0)"
K6_QUEUE_ENTRIES="$(metric_count queue_entries 0)"
K6_QUEUE_ENTRY_FAILURES="$(metric_count queue_entry_failures 0)"
K6_RESERVATION_ATTEMPTS="$(metric_count reservation_attempts 0)"
K6_RESERVATION_SUCCESS="$(metric_count reservation_success 0)"
K6_RESERVATION_CLOSED="$(metric_count reservation_closed 0)"
K6_RESERVATION_NOT_ACTIVE="$(metric_count reservation_not_active 0)"
K6_RESERVATION_SEAT_TAKEN="$(metric_count reservation_seat_taken 0)"

redis_count() {
  local pattern="$1"
  docker compose exec -T "${REDIS_SERVICE}" sh -lc "redis-cli --scan --pattern '$pattern' | wc -l | tr -d ' '"
}

mysql_scalar() {
  local query="$1"
  docker compose exec -T "${MYSQL_SERVICE}" mysql -uroot -proot -N -B ticketing -e "$query"
}

redis_reserved_seats="$(redis_count "seat:${EVENT_ID}:*")"
redis_reserved_users="$(redis_count "reservation:user:${EVENT_ID}:*")"
mysql_reserved_rows="$(mysql_scalar "SELECT COUNT(*) FROM reservations WHERE event_id='${EVENT_ID}' AND status='RESERVED';")"
mysql_duplicate_seats="$(mysql_scalar "SELECT COUNT(*) FROM (SELECT seat_id FROM reservations WHERE event_id='${EVENT_ID}' GROUP BY seat_id HAVING COUNT(*) > 1) duplicated_seats;")"
mysql_duplicate_users="$(mysql_scalar "SELECT COUNT(*) FROM (SELECT user_id FROM reservations WHERE event_id='${EVENT_ID}' GROUP BY user_id HAVING COUNT(*) > 1) duplicated_users;")"

success_count="${mysql_reserved_rows}"
failed_count=$((TOTAL_REQUESTS - success_count))
if (( failed_count < 0 )); then
  failed_count=0
fi
over_capacity_count=0
if (( mysql_reserved_rows > SEAT_CAPACITY )); then
  over_capacity_count=$((mysql_reserved_rows - SEAT_CAPACITY))
fi

cat <<REPORT
# Reservation Flow Verification

eventId: ${EVENT_ID}
totalRequests: ${TOTAL_REQUESTS}
seatCapacity: ${SEAT_CAPACITY}

예매 실패된 총 인원: ${failed_count} 명
예매 성공한 총 인원: ${mysql_reserved_rows} 명
좌석 중복 입석: ${mysql_duplicate_seats} 개
사용자 중복 예매: ${mysql_duplicate_users} 개
좌석 수 초과 예약: ${over_capacity_count} 건

K6 완료 iteration 수: ${K6_ITERATIONS}
K6 dropped iteration 수: ${K6_DROPPED_ITERATIONS}
K6 대기열 진입 성공 수: ${K6_QUEUE_ENTRIES}
K6 대기열 진입 실패 수: ${K6_QUEUE_ENTRY_FAILURES}
K6 예매 시도 수: ${K6_RESERVATION_ATTEMPTS}
K6 예매 성공 응답 수: ${K6_RESERVATION_SUCCESS}
K6 예매 마감/타임아웃 수: ${K6_RESERVATION_CLOSED}
K6 NOT_ACTIVE 수: ${K6_RESERVATION_NOT_ACTIVE}
K6 좌석 선점 충돌 수: ${K6_RESERVATION_SEAT_TAKEN}

Redis 예약 좌석 수: ${redis_reserved_seats}
Redis 예약 사용자 수: ${redis_reserved_users}
MySQL RESERVED row 수: ${mysql_reserved_rows}

정상 기준:
- 예매 성공한 총 인원은 seatCapacity(${SEAT_CAPACITY}) 이하여야 한다.
- 좌석 중복 입석은 0개여야 한다.
- 사용자 중복 예매는 0개여야 한다.
- 좌석 수 초과 예약은 0건이어야 한다.
- worker 반영 완료 후 Redis 예약 수와 MySQL RESERVED row 수가 같아야 한다.
REPORT

if (( mysql_reserved_rows > SEAT_CAPACITY )); then
  echo "FAIL: MySQL reservation count exceeds seat capacity" >&2
  exit 1
fi
if (( mysql_duplicate_seats != 0 )); then
  echo "FAIL: duplicate reserved seats detected" >&2
  exit 1
fi
if (( mysql_duplicate_users != 0 )); then
  echo "FAIL: duplicate user reservations detected" >&2
  exit 1
fi
if (( redis_reserved_seats != redis_reserved_users )); then
  echo "FAIL: Redis seat/user reservation counts differ" >&2
  exit 1
fi
if (( redis_reserved_seats != mysql_reserved_rows )); then
  echo "WARN: Redis/MySQL counts differ. The async worker may still be catching up." >&2
fi
