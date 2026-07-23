package com.ticketbooking.service;

import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * Thin wrapper around the decrement_if_available.lua script: an atomic
 * "check remaining > 0, then decrement" against a single Redis key, with no
 * external locking needed because the whole check-and-decrement runs as one
 * indivisible Redis operation.
 *
 * Gated identically to its only consumer, RedisAtomicCounterBookingService.
 * This bean depends on RedissonClient, and RedissonClient connects to Redis
 * eagerly at construction (see RedisConfig's @Lazy comment) -- without this
 * same @ConditionalOnProperty gate, this bean would be created in every
 * Spring context regardless of strategy, forcing that eager connection
 * attempt everywhere and defeating the whole point of @Lazy on
 * RedissonClient. (This exact bug shipped once already: every non-redis-counter
 * test context failed to boot because of it.)
 */
@Component
@ConditionalOnProperty(name = "app.booking.strategy", havingValue = "redis-counter")
class RedisAvailabilityCounter {

    private static final String DECREMENT_SCRIPT = loadScript();

    private final RedissonClient redissonClient;

    RedisAvailabilityCounter(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    private static String loadScript() {
        try (InputStream in = new ClassPathResource("redis/decrement_if_available.lua").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load decrement_if_available.lua", e);
        }
    }

    private static String counterKey(Long eventId) {
        return "booking:available:event:" + eventId;
    }

    /**
     * Attempts to reserve one seat's worth of the counter. fallbackInitialValue
     * is only used the first time this event's key is touched -- it should be
     * the current true count from MySQL, so a cold/lost Redis key re-syncs to
     * reality instead of silently starting from zero.
     */
    boolean tryReserve(Long eventId, long fallbackInitialValue) {
        RScript script = redissonClient.getScript(StringCodec.INSTANCE);
        Long remaining = script.eval(
                RScript.Mode.READ_WRITE,
                DECREMENT_SCRIPT,
                RScript.ReturnType.INTEGER,
                Collections.singletonList(counterKey(eventId)),
                fallbackInitialValue);
        return remaining != null && remaining >= 0;
    }

    /** Compensating increment if the MySQL write fails after a successful reserve. */
    void release(Long eventId) {
        redissonClient.getAtomicLong(counterKey(eventId)).incrementAndGet();
    }
}
