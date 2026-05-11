import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';

const preset = __ENV.PRESET || 'smoke';
const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const eventId = __ENV.EVENT_ID || `queue-${preset}-${Date.now()}`;
const pollAfterSeconds = Number(__ENV.POLL_AFTER_SECONDS || '5');
const maxPolls = Number(__ENV.MAX_POLLS || '12');

const presets = {
  smoke: {
    vus: 100,
    iterations: 100,
    thresholds: {
      http_req_failed: ['rate<0.01'],
      http_req_duration: ['p(95)<1000'],
    },
  },
  queue_only_30000: {
    vus: 30000,
    iterations: 30000,
    thresholds: {
      http_req_failed: ['rate<0.05'],
      http_req_duration: ['p(95)<2000'],
    },
  },
};

const selected = presets[preset] || presets.smoke;

export const options = {
  scenarios: {
    queue_admission: {
      executor: 'shared-iterations',
      vus: selected.vus,
      iterations: selected.iterations,
      maxDuration: __ENV.MAX_DURATION || '10m',
    },
  },
  thresholds: selected.thresholds,
};

export default function () {
  const userId = `user-${exec.scenario.iterationInTest}`;
  const entry = enterQueue(userId);

  if (!entry || !entry.queueToken) {
    return;
  }

  pollUntilTerminal(entry.queueToken);
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
        preset,
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

  return accepted ? parseJson(response) : null;
}

function pollUntilTerminal(queueToken) {
  for (let attempt = 0; attempt < maxPolls; attempt += 1) {
    const response = http.get(`${baseUrl}/api/events/${eventId}/queue/${queueToken}`, {
      tags: {
        api: 'queue-status',
        preset,
      },
    });

    const body = parseJson(response);
    check(response, {
      'queue status returned 200': (res) => res.status === 200,
      'queue status is valid': () => ['WAITING', 'ENTERED', 'EXPIRED'].includes(body && body.status),
    });

    if (body && (body.status === 'ENTERED' || body.status === 'EXPIRED')) {
      return;
    }

    sleep(body && body.pollAfterSeconds ? body.pollAfterSeconds : pollAfterSeconds);
  }
}

function parseJson(response) {
  try {
    return response.json();
  } catch (error) {
    return null;
  }
}
