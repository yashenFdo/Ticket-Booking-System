-- KEYS[1] = counter key holding the remaining seat count for one event
-- ARGV[1] = fallback initial value to seed the counter with if it doesn't
--           exist yet (adopted from MySQL's current AVAILABLE count, so a
--           cold or lost Redis key re-syncs to the true state instead of
--           starting at 0 or some stale cached value)
--
-- Atomicity note: Redis runs an entire script as one operation -- no other
-- client's commands or scripts can interleave between the EXISTS check and
-- the DECR below. That is what makes this safe with zero external locking;
-- a plain GET-then-DECR from application code would have exactly the same
-- read-check-write race as the Phase 1 naive service, just against Redis
-- instead of MySQL.
--
-- Returns the remaining count after decrementing, or -1 if sold out (in
-- which case no decrement happens).
if redis.call('EXISTS', KEYS[1]) == 0 then
    redis.call('SET', KEYS[1], ARGV[1])
end

local current = tonumber(redis.call('GET', KEYS[1]))
if current <= 0 then
    return -1
end

redis.call('DECR', KEYS[1])
return current - 1
