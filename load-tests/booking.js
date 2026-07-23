import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
// V2__seed_event.sql creates exactly one event in a freshly reset DB, so its
// auto-increment id is always 1. scripts/bench.sh resets the DB (docker
// compose down -v) before every run specifically to keep this valid.
const EVENT_ID = __ENV.EVENT_ID || 1;

export const options = {
  scenarios: {
    spike: {
      executor: 'per-vu-iterations',
      vus: 1000,
      // 5 iterations/VU = 5000 requests total against 1000 seats. This is
      // deliberate: with exactly 1000 requests against 1000 seats,
      // bookingsCreated can never exceed capacity by definition, so
      // oversoldBy would read 0 even though the race is real (it would
      // only show up as duplicateSeatAssignments). Requesting more than
      // capacity in a single zero-ramp-up burst is what turns the race into
      // a measurable oversell.
      iterations: 5,
      maxDuration: '2m',
    },
  },
};

export default function () {
  const res = http.post(
    `${BASE_URL}/api/bookings`,
    JSON.stringify({ eventId: EVENT_ID }),
    { headers: { 'Content-Type': 'application/json' } },
  );

  // 201 (booked) and 409 (sold out) are both "the API behaved sanely";
  // this check is not a correctness assertion -- /audit is the correctness
  // check. Anything else (500, timeout) is a real bug in the endpoint.
  check(res, {
    'status is 201 or 409': (r) => r.status === 201 || r.status === 409,
  });
}
