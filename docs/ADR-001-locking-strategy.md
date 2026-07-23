# ADR-001: Pessimistic vs Optimistic Locking for Seat Booking

## Status

Accepted (Phase 4). Default strategy remains pessimistic.

## Context

Both `PessimisticBookingService` (Phase 3) and `OptimisticBookingService`
(Phase 4) solve the same problem -- the Phase 1 naive race -- with opposite
philosophies:

- **Pessimistic**: assume a conflict will happen, so prevent it. `SELECT ...
  FOR UPDATE` takes a real row lock; a second transaction racing for the
  same seat blocks until the first commits.
- **Optimistic**: assume a conflict is rare, so detect it after the fact.
  Read the seat unguarded, write it back with a `@Version` check; if
  someone else already wrote it, the write is rejected and retried.

Both are measured in `docs/BENCHMARKS.md` against the same load pattern.

## The contention pattern in this project is a worst case for optimistic locking

Both services pick "the lowest `seat_number` currently `AVAILABLE`" for the
event -- i.e. every request funnels onto the *same* one or two rows instead
of spreading across the pool of available seats. That's deliberate: it's
the pattern that makes the naive baseline oversell visibly, but it also
means optimistic locking here sees far more retries than it would if
requests picked a random available seat instead. Worth remembering when
reading the benchmark numbers: they measure locking strategy under an
artificially concentrated contention pattern, not the best case for either
approach.

## When optimistic locking wins

- Low-to-moderate contention -- most transactions don't actually collide,
  so most requests succeed on the first attempt with no lock ever held.
- Short, fast transactions -- a failed attempt is cheap to retry.
- Read-heavy or mixed workloads where blocking writers on a row lock would
  stall unrelated readers/writers that don't actually conflict.
- Limited DB connection pools -- no thread sits blocked holding a
  connection while waiting on a lock; it fails fast and frees the
  connection immediately.

## When optimistic locking is catastrophically worse

- **High contention on the same row** -- exactly this project's pattern.
  Every loser retries, and the retry can land on the *same* now-current
  version of the row and collide again. Each attempt costs a full DB round
  trip that accomplishes nothing when it fails, whereas a blocked
  pessimistic waiter accomplishes its work the instant the lock is free.
  At high enough contention, optimistic locking can have **lower**
  throughput than pessimistic locking despite never blocking, because it
  is doing strictly more failed work per success.
- **Bounded retries can still fail even though inventory exists.**
  `OptimisticBookingService` gives up after `MAX_ATTEMPTS` (5) and returns
  409. A caller can be told "no seat" while seats are objectively still
  available elsewhere in the table -- the retry loop exhausted itself on
  one contended row, not on true exhaustion of supply. Pessimistic locking
  doesn't have this failure mode: a waiter either eventually gets the lock
  or the lock-wait timeout fires, and the timeout is under our control
  (Phase 3 uses 3s), not bounded by an attempt count that's blind to how
  long each attempt actually took.
- **Long-running transactions** widen the window in which a conflicting
  write can land, increasing collision probability -- the opposite of what
  makes optimistic locking attractive.
- **Retry storms compound under load.** More retries mean more DB load
  from failed attempts, which slows down the transactions that could have
  succeeded, which increases the collision window for everyone else. This
  is a positive feedback loop that pessimistic locking does not have,
  since waiters never do wasted work.

## Decision

Keep pessimistic locking (`app.booking.strategy=pessimistic`) as the
default: seat booking is fundamentally a "claim one of N interchangeable
resources" pattern, which is exactly what `SELECT ... FOR UPDATE` handles
without wasted work, and it degrades predictably (bounded lock-wait
timeout) rather than unpredictably (retries that may or may not converge).

Optimistic locking is kept in the codebase and benchmarked because it
demonstrates the alternative philosophy and because its retry/exhaustion
counters (`booking.optimistic.retry.attempts`,
`booking.optimistic.retry.exhausted`) are themselves useful signals for
deciding, on a real system, whether contention is low enough for optimistic
locking to be worth its lower idle-case overhead.

## A cheaper fix than switching strategies: stop concentrating the contention

Neither locking strategy is the only lever here. If seat assignment didn't
need to be "lowest number first" (e.g. picked a random available seat
instead), both strategies would see collisions drop sharply, since
concurrent requests would mostly target different rows. That change is
deliberately *not* made in this project -- the point is to demonstrate and
measure locking strategies under real contention, not to engineer the
contention away -- but it is the first thing worth trying on a real system
before reaching for a different locking strategy.
