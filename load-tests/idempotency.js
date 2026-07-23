import http from 'k6/http';
import { check } from 'k6';

// Assumes a freshly reset DB (docker compose down -v && up, same as
// booking.js) so the seeded event has zero prior bookings -- the teardown
// assertion below checks bookingsCreated equals exactly DISTINCT_KEYS,
// which only holds if nothing booked against this event before this run.
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const EVENT_ID = __ENV.EVENT_ID || 1;
const DISTINCT_KEYS = 20;
const RETRIES_PER_KEY = 10;

export const options = {
  scenarios: {
    aggressive_retries: {
      executor: 'per-vu-iterations',
      vus: DISTINCT_KEYS,
      iterations: RETRIES_PER_KEY,
      maxDuration: '2m',
    },
  },
};

export default function () {
  // __VU is stable for the lifetime of a VU, so every iteration from the
  // same VU reuses the same key -- simulating a client that aggressively
  // retries the same logical booking request (e.g. after a client-side
  // timeout), including retries that race with the still-in-flight
  // original request.
  const idempotencyKey = `k6-idempotency-key-${__VU}`;

  const res = http.post(
    `${BASE_URL}/api/bookings`,
    JSON.stringify({ eventId: EVENT_ID }),
    {
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
      },
    },
  );

  check(res, {
    'status is 201/404/409': (r) => [201, 404, 409].includes(r.status),
  });
}

// The actual point of this scenario: DISTINCT_KEYS * RETRIES_PER_KEY
// requests go out, but only DISTINCT_KEYS logical bookings should ever be
// created -- retries (sequential or concurrent) with the same key must
// replay the original outcome, not create a new booking each time.
export function teardown() {
  const auditRes = http.get(`${BASE_URL}/api/events/${EVENT_ID}/audit`);
  const audit = JSON.parse(auditRes.body);
  check(audit, {
    'bookingsCreated equals distinct idempotency keys, not total requests':
      (a) => a.bookingsCreated === DISTINCT_KEYS,
  });
}
