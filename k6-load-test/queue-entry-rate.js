import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const eventId = __ENV.EVENT_ID || `queue-entry-rate-${Date.now()}`;
const rate = Number(__ENV.RATE || '10000');
const duration = __ENV.DURATION || '1s';
const preAllocatedVUs = Number(__ENV.PRE_ALLOCATED_VUS || '10000');
const maxVUs = Number(__ENV.MAX_VUS || '12000');

const queueEntries = new Counter('queue_entries');
const invalidResponses = new Counter('queue_entry_invalid');

export const options = {
  scenarios: {
    queue_entry_rate: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs,
      maxVUs,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<2000'],
  },
};

export default function () {
  const userId = `user-${exec.scenario.iterationInTest}`;
  const response = http.post(
    `${baseUrl}/api/events/${eventId}/queue`,
    JSON.stringify({ userId }),
    {
      headers: {
        'Content-Type': 'application/json',
      },
      tags: {
        api: 'queue-entry',
        scenario: 'queue-entry-rate',
      },
    }
  );

  const accepted = check(response, {
    'queue entry returned 200': (res) => res.status === 200,
    'queue entry returned token': (res) => {
      const body = parseJson(res);
      return Boolean(body && body.queueToken);
    },
  });

  if (accepted) {
    queueEntries.add(1);
    return;
  }

  invalidResponses.add(1);
}

function parseJson(response) {
  try {
    return response.json();
  } catch (error) {
    return null;
  }
}
