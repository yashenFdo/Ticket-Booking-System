package com.ticketbooking.service;

import com.ticketbooking.repository.EventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Fixes the Phase 1 race without taking a row lock: read the seat
 * unguarded (same query the naive service uses), then let the @Version
 * check at write time reject stale writes. A rejected write is retried a
 * bounded number of times with exponential backoff + full jitter, rather
 * than looping tightly (which would just re-collide immediately) or
 * retrying forever (which would let one contended seat starve a request
 * indefinitely).
 *
 * Tradeoff vs pessimistic locking: no thread ever blocks waiting on a lock,
 * so throughput under LOW contention is typically higher. Under HIGH
 * contention on the same handful of rows, retries compound -- every
 * failed attempt burns a DB round trip and still might collide again,
 * which is why this can be worse than pessimistic locking at high
 * contention. See docs/ADR-001-locking-strategy.md.
 */
@Service
@ConditionalOnProperty(name = "app.booking.strategy", havingValue = "optimistic")
public class OptimisticBookingService implements BookingService {

    private static final int MAX_ATTEMPTS = 5;
    private static final long BASE_BACKOFF_MILLIS = 20;

    private final EventRepository eventRepository;
    private final OptimisticSeatBooker seatBooker;
    private final Counter retryCounter;
    private final Counter retriesExhaustedCounter;

    public OptimisticBookingService(EventRepository eventRepository,
                                     OptimisticSeatBooker seatBooker,
                                     MeterRegistry meterRegistry) {
        this.eventRepository = eventRepository;
        this.seatBooker = seatBooker;
        this.retryCounter = Counter.builder("booking.optimistic.retry.attempts")
                .description("Optimistic-lock retry attempts for seat booking")
                .register(meterRegistry);
        this.retriesExhaustedCounter = Counter.builder("booking.optimistic.retry.exhausted")
                .description("Bookings that exhausted all optimistic-lock retry attempts")
                .register(meterRegistry);
    }

    @Override
    public BookingResult book(Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new EventNotFoundException(eventId);
        }

        OptimisticLockingFailureException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return seatBooker.attempt(eventId);
            } catch (OptimisticLockingFailureException ex) {
                lastFailure = ex;
                retryCounter.increment();
                if (attempt < MAX_ATTEMPTS) {
                    backoffWithJitter(eventId, attempt);
                }
            }
        }

        retriesExhaustedCounter.increment();
        throw new BookingConflictException(eventId, lastFailure);
    }

    private void backoffWithJitter(Long eventId, int attempt) {
        // Exponential backoff (base * 2^(attempt-1)) with full jitter:
        // threads that just lost a version race and retried at a fixed
        // delay would tend to collide again on the next attempt. Jitter
        // spreads their wake-up times so they don't all re-converge on the
        // same seat at the same instant.
        long maxDelayMillis = BASE_BACKOFF_MILLIS * (1L << (attempt - 1));
        long delayMillis = ThreadLocalRandom.current().nextLong(maxDelayMillis + 1);
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BookingConflictException(eventId, e);
        }
    }
}
