# Benchmarks

Every number in this table comes from a real k6 run against real MySQL + Redis
(via `docker compose` and the `bench` script). Nothing here is estimated —
if a row isn't filled in, that phase hasn't been benchmarked yet.

| Strategy | Throughput (req/s) | p95 | p99 | Errors | Oversold |
|---|---|---|---|---|---|
| Naive (baseline) | TBD | TBD | TBD | TBD | TBD |
| Pessimistic lock | TBD | TBD | TBD | TBD | TBD |
| Optimistic + retry | TBD | TBD | TBD | TBD | TBD |
| Redis distributed lock | TBD | TBD | TBD | TBD | TBD |
| Redis atomic counter | TBD | TBD | TBD | TBD | TBD |
