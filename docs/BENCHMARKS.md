# Benchmarks

Every number in this table comes from a real k6 run against real MySQL + Redis
(via `docker compose` and the `bench` script). Nothing here is estimated —
if a row isn't filled in, that phase hasn't been benchmarked yet.

**Status as of Phase 2: not yet run.** This dev machine has no Docker
installed, so `scripts/bench.sh` (needs `docker`, `mvn`, `java`, `k6`, `curl`
on PATH) has not been executed. `load-tests/booking.js` and the bench script
are written and ready; run `scripts/bench.sh` once Docker + k6 are available
and paste the real output into the table below. Do not fill in numbers any
other way.

| Strategy | Throughput (req/s) | p95 | p99 | Errors | Oversold |
|---|---|---|---|---|---|
| Naive (baseline) | TBD | TBD | TBD | TBD | TBD |
| Pessimistic lock | TBD | TBD | TBD | TBD | TBD |
| Optimistic + retry | TBD | TBD | TBD | TBD | TBD |
| Redis distributed lock | TBD | TBD | TBD | TBD | TBD |
| Redis atomic counter | TBD | TBD | TBD | TBD | TBD |
