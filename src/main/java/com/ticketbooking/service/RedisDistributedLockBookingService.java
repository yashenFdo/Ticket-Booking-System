package com.ticketbooking.service;

import com.ticketbooking.repository.EventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Fixes the race by taking a lock outside the database entirely: one
 * Redisson RLock per event, so every app instance in the cluster (not just
 * threads within one JVM) serializes on the same key. This is what makes it
 * a real fix for a load-balanced deployment, unlike synchronized/
 * ReentrantLock, which only coordinates threads inside a single process.
 *
 * No explicit leaseTime is passed to tryLock, which means Redisson's
 * watchdog manages the lock: it holds a default 30s lease and renews it
 * (roughly every 10s) for as long as this thread is alive and still holds
 * the lock. A normal request here is one fast DB round trip, so under
 * normal operation the lease can never expire mid-operation. See
 * docs/ADR-002-redis-locking.md for what "normal operation" is excluding
 * (GC pauses, Redis failover) and why that matters.
 *
 * Once the lock is held, no DB-level locking is needed -- OptimisticSeatBooker's
 * plain unguarded read+write is reused as-is, because the Redis lock already
 * guarantees this is the only caller touching this event's seats anywhere
 * in the cluster.
 */
@Service
@ConditionalOnProperty(name = "app.booking.strategy", havingValue = "redis-lock")
public class RedisDistributedLockBookingService implements BookingService {

    private static final long WAIT_TIME_MS = 3000;

    private final RedissonClient redissonClient;
    private final EventRepository eventRepository;
    private final OptimisticSeatBooker seatBooker;
    private final Timer lockWaitTimer;

    public RedisDistributedLockBookingService(RedissonClient redissonClient,
                                               EventRepository eventRepository,
                                               OptimisticSeatBooker seatBooker,
                                               MeterRegistry meterRegistry) {
        this.redissonClient = redissonClient;
        this.eventRepository = eventRepository;
        this.seatBooker = seatBooker;
        this.lockWaitTimer = Timer.builder("booking.lock.wait")
                .description("Time spent acquiring a seat lock before booking")
                .tag("strategy", "redis-lock")
                .register(meterRegistry);
    }

    @Override
    public BookingResult book(Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new EventNotFoundException(eventId);
        }

        RLock lock = redissonClient.getLock("booking:lock:event:" + eventId);
        boolean acquired;
        Timer.Sample sample = Timer.start();
        try {
            acquired = lock.tryLock(WAIT_TIME_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BookingConflictException(eventId, e);
        } finally {
            sample.stop(lockWaitTimer);
        }

        if (!acquired) {
            throw new BookingConflictException(eventId);
        }

        try {
            return seatBooker.attempt(eventId);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
