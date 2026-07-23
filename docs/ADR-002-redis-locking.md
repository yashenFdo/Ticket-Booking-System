# ADR-002: Redis-Based Concurrency Control and Its Failure Modes

## Status

Accepted (Phase 5). Two strategies implemented: `redis-lock`
(`RedisDistributedLockBookingService`) and `redis-counter`
(`RedisAtomicCounterBookingService`).

## Why Redis at all, given Phases 3-4 already fixed the race

Pessimistic and optimistic locking (Phases 3-4) only coordinate transactions
*within a single MySQL instance*. That's sufficient here because MySQL is
the only place seat state lives. Redis becomes relevant the moment you
want to serialize something that MySQL's row locks can't reach on their
own -- coordinating multiple *application instances* around a resource
before they ever touch the database, or moving the hot-path check off the
primary database entirely. This project implements both to demonstrate and
benchmark the tradeoff, not because MySQL locking was insufficient.

## Why not `synchronized` / `ReentrantLock`

Per the project's hard rules: a JVM-local lock only coordinates threads
inside one process. Behind a load balancer with multiple instances of this
service running, two different instances would each happily grant their
own local lock for the same event and both proceed -- `synchronized` would
"fix" the race in a single-instance load test and then fail exactly the
same way in production the moment a second instance is deployed.

## Failure mode 1: lock expiry mid-operation

A Redisson lock is a key with a TTL. If that TTL expires while the holder
is still working, another client can acquire the "same" lock and both
believe they hold it -- the classic double-booking-despite-a-lock bug.

`RedisDistributedLockBookingService` avoids the most common cause of this
by not passing an explicit `leaseTime` to `tryLock`: Redisson's **watchdog**
then holds a 30s lease and renews it (~every 10s) for as long as the owning
thread is alive and hasn't unlocked. Since a booking here is one fast DB
round trip, the lease can't expire mid-operation under normal conditions.

This does **not** eliminate the risk entirely:

- If the JVM pauses long enough (a stop-the-world GC pause, or the host
  being descheduled/suspended) that the watchdog's renewal never reaches
  Redis before the current lease's TTL elapses, the lock can still expire
  while the thread believes it's still holding it.
- If someone later "optimizes" this code to pass an explicit `leaseTime`
  (disabling the watchdog) without also guaranteeing the operation finishes
  well within that lease, the same failure returns deliberately instead of
  by accident.

## Failure mode 2: Redis failover losing the lock

`RedisConfig` here points at a single Redis node -- there is no replica or
failover configured, so this failure mode is latent, not currently live in
this project. It matters as soon as you add one:

Redis replication is asynchronous by default. If a client acquires a lock
on the master, and the master crashes *before* the `SET` replicates to the
replica, and the replica is then promoted to the new master, the new
master has no record the lock was ever granted. A second client can
acquire the "same" lock on the new master while the first client still
believes it holds it. The lock survived a promotion by silently not being
there.

## Why Redlock is contested

Redlock (acquire the lock against a majority of N independent Redis
masters) is Redis's own proposed answer to failure mode 2 -- no single
master's crash can lose the lock, since a majority must agree.

Martin Kleppmann's widely-cited critique argues Redlock still doesn't
deliver the mutual-exclusion *safety* guarantee people assume from it,
because it implicitly assumes bounded clocks and bounded delays: a GC
pause, a slow network, or a clock jump between "I confirmed I still hold
the lock" and "I acted on the resource" can make a lock holder act after
its lock has actually expired, on a majority of masters, without anyone
having lied to it. Redlock improves *availability* (no single point of
failure) but does not, by itself, prevent two holders from both believing
they hold the lock at the same instant.

The rigorous fix is a **fencing token**: a monotonically increasing number
handed out with the lock, which the protected resource itself validates
(e.g., the database rejects a write carrying an older token than one it has
already seen). This project does not implement fencing tokens -- it's the
right call for genuinely dangerous, hard-to-undo external side effects
(charging a card, calling another service) held under a *long* lock, and
overkill for a lock held for the duration of one fast local MySQL
transaction, where MySQL's own transactional guarantees are the actual
backstop if the Redis lock's exclusivity is ever violated. Worth knowing
the gap exists even when choosing not to close it.

## Decision

Keep both Redis strategies for benchmarking, with the single-node lock as
implemented. If this project ever added Redis replication/Sentinel, adding
fencing tokens (or moving to Redlock across independent masters) would be
the next hardening step, not something to skip silently.
