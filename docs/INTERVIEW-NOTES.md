# Interview Notes

Quick-reference answers for "why did you do X" questions about this
project, plus the one bug worth telling a war story about.

## Per-strategy: when to use it, when not to

**Naive (read-check-write, no lock)** — never, anywhere. It exists as the
control group: the number every other strategy is measured against.
Broken by construction (see `NaiveBookingService`'s class comment).

**Pessimistic locking (`SELECT ... FOR UPDATE`)** — use when the resource
being claimed is a small pool of interchangeable things ("claim one of N
seats") and correctness matters more than raw throughput under contention.
Degrades predictably: a bounded lock-wait timeout, not an unbounded retry
count. Weakness: every contending request either waits or times out — no
wasted work, but also no way to "fail fast and let the client decide."
Full writeup: [`ADR-001`](ADR-001-locking-strategy.md).

**Optimistic locking (`@Version` + retry/backoff/jitter)** — use when
contention on any single row is low-to-moderate and transactions are
short, so most requests succeed on the first attempt with zero lock held.
Gets *worse* than pessimistic locking specifically under high contention on
the *same* row, because every failed attempt is a wasted round trip that
accomplishes nothing — this project's "always pick the lowest available
seat_number" selection is a deliberately worst-case pattern for it. Also:
bounded retries can return "no seat" even when inventory objectively still
exists elsewhere in the table. Full writeup: same ADR-001.

**Redis distributed lock (Redisson `RLock`)** — use when correctness needs
to be coordinated across *multiple application instances*, not just
threads in one JVM (which is exactly why `synchronized`/`ReentrantLock`
are disallowed here — they'd pass a single-instance load test and then
silently break the moment a second instance goes behind the load
balancer). Relies on Redisson's watchdog to avoid the lock expiring
mid-operation; that protection has real limits (GC pauses, Redis
failover). Full writeup: [`ADR-002`](ADR-002-redis-locking.md).

**Redis atomic counter (Lua decrement)** — use when you want the hot-path
admission check off the primary database entirely and are willing to
accept a slightly more complex reconciliation story (the counter and
MySQL are two systems that can't be updated atomically together). No lock
contention at all — the Lua script's atomicity comes from Redis being
single-threaded per script execution, not from waiting on anything.

## The hardest bug caught in this build

This project was built without Docker available on the dev machine, so
nothing described below was caught by a failing test — it was caught by
reasoning through Spring Data JPA semantics before ever running the code,
which is a weaker signal than a real failure and is called out as such.

**The bug:** `BookingIdempotencyRecord` (Phase 6) has an
application-assigned `@Id` (the idempotency key string itself, not a
database-generated one). Spring Data JPA's default `isNew()` check for
deciding whether `repository.save()` should `persist()` (INSERT) or
`merge()` (SELECT-then-insert-or-update) is "is the ID field null?" — and
for a freshly constructed `BookingIdempotencyRecord`, the ID is *not* null,
because the caller sets it explicitly in the constructor. Left as-is,
`save()` would have silently gone through `merge()` for a brand-new
instance, which does not fail on a duplicate key: it just overwrites the
row that's already there.

That's fatal specifically for this feature: the entire "claim a key by
inserting a row, and let a genuine INSERT conflict tell you someone else
already claimed it" mechanism depends on the second claimant's write
*failing*, not quietly succeeding as an overwrite. With `merge()`, two
concurrent requests carrying the same `Idempotency-Key` would both believe
they'd claimed the key, both proceed to book a seat, and idempotency would
silently not exist under the one condition (concurrent duplicates) it was
built to handle.

**How it surfaced:** while writing `IdempotentBookingHandler.tryClaim()`
and reasoning about what happens when two threads call it with the same
key at the same instant, tracing through what `save()` actually does for
an entity with a non-generated ID is what raised the flag — a known,
named Spring Data JPA gotcha (natural/assigned-key entities and
`merge()` vs `persist()`), not something novel to this project.

**The fix:** implement `Persistable<String>` with an explicit transient
`isNew` flag, defaulted `true` on construction and flipped to `false` by
`@PostLoad`/`@PostPersist`. This forces `persist()` for genuinely new
instances, so a second claimant's `save()` now correctly throws
`DataIntegrityViolationException` on the primary-key collision instead of
overwriting the winner's row.

**What this means for anyone continuing this project:** the fact that
this was caught by reading code rather than by a red test is a gap, not a
strength. The very first thing to do once Docker is available should be
running `IdempotencyIntegrationTest`'s concurrent-duplicate test — if the
`Persistable` fix above has a subtle error, that's exactly the test that
would catch it, and until it's actually been run, "this works" is a claim
resting on reasoning alone.

## A second bug in the same category, wider blast radius

`RedisConfig` (Phase 5) originally registered `RedissonClient` as a plain,
unconditional `@Bean`. Redisson connects to Redis *eagerly*, at client
construction time, not on first use. Only two of the five booking
strategies (`redis-lock`, `redis-counter`) ever actually inject a
`RedissonClient` -- but a plain `@Bean` gets constructed on every context
startup regardless of who ends up using it. That would have meant every
other test in this project (naive, pessimistic, optimistic, idempotency,
availability -- none of which start a Redis container) failing to boot
its Spring context at all, trying to connect to a Redis at `localhost:6379`
that was never running.

Same root cause as the `Persistable` bug: an assumption about *when*
Spring/a library actually does the expensive/dangerous thing, not verified
by running anything. The fix here was smaller -- `@Lazy` on the bean, so
it's only constructed when something actually injects it -- but the blast
radius, had it shipped unnoticed, would have been much larger: not one
feature's concurrent-duplicate test, but nearly the entire test suite
failing to start.

**Takeaway worth saying out loud in an interview:** both bugs share a
pattern -- a framework/library default that is fine in the common case
(generated IDs; a single strategy's worth of Redis usage) and silently
wrong in this project's specific shape (a natural key; five strategies
sharing one Spring context type across many test classes). Neither was
caught by running anything, because nothing could be run. That is a real
limitation of this build, not a detail to gloss over.
