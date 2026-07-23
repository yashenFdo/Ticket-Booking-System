# Benchmarks

Every number in this table comes from a real k6 run against real MySQL + Redis
(via `docker compose` and the `bench` script). Nothing here is estimated —
if a row isn't filled in, that phase hasn't been benchmarked yet.

**Status: builds clean, not yet benchmarked.** `mvn compile`, `test-compile`,
and `package` all succeed (verified locally via a Maven distribution
bundled with IntelliJ, since `mvn` isn't separately installed) -- the code
compiles and packages into a runnable jar. Docker is still not installed on
this machine, so nothing that needs MySQL/Redis (the Testcontainers test
suite, `scripts/bench.sh`, the actual k6 runs) has executed yet. To
benchmark a strategy: set
`app.booking.strategy` (naive|pessimistic|optimistic|redis-lock|redis-counter)
in `application.yml` or via an env var, restart the app, run
`scripts/bench.sh`, and paste the real output into the table below. Do not
fill in numbers any other way.

`PessimisticBookingConcurrencyTest`, `OptimisticBookingConcurrencyTest`,
`RedisLockBookingConcurrencyTest`, and `RedisCounterBookingConcurrencyTest`
(200 threads vs 50 seats each) are written and ready to run once Docker is
available. Naive/pessimistic benchmark runs should be taken from the
`phase-1-naive-baseline` / `phase-3-pessimistic-locking` git tags rather
than current HEAD -- see the note in `Seat.java` about `@Version` becoming
a table-wide concern from Phase 4 onward.

| Strategy | Throughput (req/s) | p95 | p99 | Errors | Oversold |
|---|---|---|---|---|---|
| Naive (baseline) | TBD | TBD | TBD | TBD | TBD |
| Pessimistic lock | TBD | TBD | TBD | TBD | TBD |
| Optimistic + retry | TBD | TBD | TBD | TBD | TBD |
| Redis distributed lock | TBD | TBD | TBD | TBD | TBD |
| Redis atomic counter | TBD | TBD | TBD | TBD | TBD |
