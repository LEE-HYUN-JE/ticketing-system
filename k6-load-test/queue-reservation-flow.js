import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://localhost:18080';
const eventId = __ENV.EVENT_ID || `queue-reservation-flow-${Date.now()}`;
const rate = Number(__ENV.RATE || '10000');
const duration = __ENV.DURATION || '1s';
const gracefulStop = __ENV.GRACEFUL_STOP || '90s';
const preAllocatedVUs = Number(__ENV.PRE_ALLOCATED_VUS || '10000');
const maxVUs = Number(__ENV.MAX_VUS || '12000');
const userPrefix = __ENV.USER_PREFIX || 'user';
const seatCapacity = Number(__ENV.SEAT_CAPACITY || '2000');
const maxPolls = Number(__ENV.MAX_POLLS || '120');
const pollIntervalSeconds = Number(__ENV.POLL_INTERVAL_SECONDS || '0.2');
const reservationWindowSeconds = Number(__ENV.RESERVATION_WINDOW_SECONDS || '2');
const reservationThinkTimeSeconds = Number(__ENV.RESERVATION_THINK_TIME_SECONDS || '0');
const logMode = __ENV.MONITOR_LOG || 'sampled';
const logSampleRate = Number(__ENV.MONITOR_LOG_SAMPLE_RATE || '100');

const queueEntries = new Counter('queue_entries');
const queueEntryFailures = new Counter('queue_entry_failures');
const queueWaiting = new Counter('queue_status_waiting');
const queueEntered = new Counter('queue_status_entered');
const queueExpired = new Counter('queue_status_expired');
const queueStatusInvalid = new Counter('queue_status_invalid');
const reservationAttempts = new Counter('reservation_attempts');
const reservationSuccess = new Counter('reservation_success');
const reservationSeatTaken = new Counter('reservation_seat_taken');
const reservationAlreadyReserved = new Counter('reservation_already_reserved');
const reservationNotActive = new Counter('reservation_not_active');
const reservationInvalid = new Counter('reservation_invalid');
const reservationClosed = new Counter('reservation_closed');

export const options = {
  scenarios: {
    queue_reservation_flow: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration,
      gracefulStop,
      preAllocatedVUs,
      maxVUs,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<2000'],
    queue_entries: ['count>0'],
  },
};

export default function () {
  const iteration = exec.scenario.iterationInTest;
  const userId = `${userPrefix}-${iteration}`;
  const entry = enterQueue(userId);

  if (!entry || !entry.queueToken) {
    queueEntryFailures.add(1);
    monitor(userId, `대기열 진입 실패`);
    return;
  }

  const entered = waitUntilEntered(userId, entry.queueToken);
  if (!entered) {
    reservationClosed.add(1);
    monitor(userId, `예매 마감으로 실패되었습니다. active 상태를 얻지 못했습니다.`);
    return;
  }

  reserveRandomSeat(userId);
}

function enterQueue(userId) {
  const response = http.post(
    `${baseUrl}/api/events/${eventId}/queue`,
    JSON.stringify({ userId }),
    {
      headers: {
        'Content-Type': 'application/json',
      },
      tags: {
        api: 'queue-entry',
        eventId,
      },
    }
  );

  const accepted = check(response, {
    'queue entry returned 200': (res) => res.status === 200,
    'queue entry returned token': (res) => Boolean(parseJson(res) && parseJson(res).queueToken),
  });

  if (accepted) {
    queueEntries.add(1);
    return parseJson(response);
  }
  return null;
}

function waitUntilEntered(userId, queueToken) {
  for (let attempt = 0; attempt < maxPolls; attempt += 1) {
    const response = http.get(`${baseUrl}/api/events/${eventId}/queue/${queueToken}`, {
      tags: {
        api: 'queue-status',
        eventId,
      },
    });
    const body = parseJson(response);

    check(response, {
      'queue status returned 200': (res) => res.status === 200,
      'queue status is valid': () => ['WAITING', 'ENTERED', 'EXPIRED'].includes(body && body.status),
    });

    if (!body || !body.status) {
      queueStatusInvalid.add(1);
      sleep(pollIntervalSeconds);
      continue;
    }

    if (body.status === 'WAITING') {
      queueWaiting.add(1);
      monitor(userId, `의 대기순번은 ${body.rank} 입니다. totalWaiting=${body.totalWaiting}`);
      sleep(pollIntervalSeconds);
      continue;
    }

    if (body.status === 'ENTERED') {
      queueEntered.add(1);
      monitor(userId, `이 Active 상태로 진입했습니다. 예매 시작 .. activeExpiresInSeconds=${body.activeExpiresInSeconds}`);
      return true;
    }

    queueExpired.add(1);
    monitor(userId, `예매 마감으로 실패되었습니다. queueToken이 EXPIRED 상태입니다.`);
    return false;
  }

  return false;
}

function reserveRandomSeat(userId) {
  const deadline = Date.now() + reservationWindowSeconds * 1000;
  let attempt = 0;

  while (Date.now() < deadline) {
    attempt += 1;
    const seatId = randomSeatId();
    const response = http.post(
      `${baseUrl}/api/events/${eventId}/reservations`,
      JSON.stringify({ userId, seatId }),
      {
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': `${userId}-reservation-attempt-${attempt}`,
        },
        tags: {
          api: 'reservation',
          eventId,
        },
      }
    );
    const body = parseJson(response);

    reservationAttempts.add(1);
    check(response, {
      'reservation returned 200': (res) => res.status === 200,
      'reservation status is valid': () => Boolean(body && body.status),
    });

    if (!body || !body.status) {
      reservationInvalid.add(1);
      sleep(reservationThinkTimeSeconds);
      continue;
    }

    if (body.status === 'RESERVED') {
      reservationSuccess.add(1);
      monitor(userId, `이 ${body.seatId} 좌석을 예매했습니다.`);
      return;
    }

    if (body.status === 'ALREADY_RESERVED') {
      reservationAlreadyReserved.add(1);
      monitor(userId, `은 이미 ${body.seatId} 좌석을 예매한 상태입니다.`);
      return;
    }

    if (body.status === 'SEAT_ALREADY_TAKEN') {
      reservationSeatTaken.add(1);
      sleep(reservationThinkTimeSeconds);
      continue;
    }

    if (body.status === 'NOT_ACTIVE') {
      reservationNotActive.add(1);
      monitor(userId, `예매 마감으로 실패되었습니다. active 상태가 아닙니다.`);
      return;
    }

    reservationInvalid.add(1);
    sleep(reservationThinkTimeSeconds);
  }

  reservationClosed.add(1);
  monitor(userId, `예매 마감으로 실패되었습니다. ${reservationWindowSeconds}초 안에 좌석을 선점하지 못했습니다.`);
}

function randomSeatId() {
  return `seat-${Math.floor(Math.random() * seatCapacity) + 1}`;
}

function monitor(userId, message) {
  if (logMode === 'off') {
    return;
  }
  const iteration = exec.scenario.iterationInTest;
  if (logMode === 'all' || iteration % logSampleRate === 0) {
    console.log(`[event=${eventId}] ${userId} ${message}`);
  }
}

function parseJson(response) {
  try {
    return response.json();
  } catch (error) {
    return null;
  }
}
