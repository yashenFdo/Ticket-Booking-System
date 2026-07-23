# Benchmarks

Every number in this table comes from a real k6 run against real MySQL + Redis
(via `docker compose` and the `bench` script). Nothing here is estimated —
if a row isn't filled in, that phase hasn't been benchmarked yet.

**Status as of Phase 3: not yet run.** This dev machine has no Docker
installed, so `scripts/bench.sh` (needs `docker`, `mvn`, `java`, `k6`, `curl`
on PATH) has not been executed. To benchmark a strategy: set
`app.booking.strategy` (naive|pessimistic) in `application.yml` or via
`SPRING_APPLICATION_JSON`/env var, restart the app, run `scripts/bench.sh`,
and paste the real output into the table below. Do not fill in numbers any
other way. `PessimisticBookingConcurrencyTest` (200 threads vs 50 seats) is
written and ready to run once Docker is available -- it is the test that is
expected to fail if pointed at `app.booking.strategy=naive` instead.

| Strategy | Throughput (req/s) | p95 | p99 | Errors | Oversold |
|---|---|---|---|---|---|
| Naive (baseline) | TBD | TBD | TBD | TBD | TBD |
| Pessimistic lock | TBD | TBD | TBD | TBD | TBD |
| Optimistic + retry | TBD | TBD | TBD | TBD | TBD |
| Redis distributed lock | TBD | TBD | TBD | TBD | TBD |
| Redis atomic counter | TBD | TBD | TBD | TBD | TBD |
