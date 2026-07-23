# Dashboard Screenshots

Pending. This directory is where the Grafana dashboard screenshot (Phase 7
deliverable) belongs, captured while `scripts/bench.sh` is generating real
load. Not yet produced because this dev machine has no Docker installed, so
Prometheus/Grafana have never actually been run -- see `docs/BENCHMARKS.md`
for the same "unverified" caveat on the rest of the stack.

Once Docker is available: `docker compose up -d`, run `scripts/bench.sh`
(or `k6 run load-tests/booking.js` directly against a running app), open
`http://localhost:3000` (Grafana, anonymous viewer access is enabled), and
save a screenshot of the "Ticket Booking Concurrency" dashboard here as
`dashboard-under-load.png`.
