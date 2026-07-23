package com.ticketbooking.service;

import com.ticketbooking.domain.SeatStatus;
import com.ticketbooking.repository.EventRepository;
import com.ticketbooking.repository.SeatRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;

/**
 * The alternative to a distributed lock: instead of serializing access to
 * MySQL, gate entry with an atomic Redis counter (decrement_if_available.lua)
 * and only touch MySQL once a slot has actually been reserved. MySQL stays
 * the system of record for which specific seat got booked and for the
 * /audit endpoint; Redis is purely the fast admission-control layer.
 *
 * Important limitation this class has to account for: the Redis gate only
 * controls HOW MANY requests get admitted, not WHICH seat row each admitted
 * request writes to. Multiple admitted requests can still race on
 * OptimisticSeatBooker's unguarded "pick the lowest available seat_number"
 * read, and since Seat.version is mapped (Phase 4), one of them will get an
 * OptimisticLockingFailureException at write time. Unlike
 * RedisDistributedLockBookingService (where the lock gives full mutual
 * exclusion, so this conflict structurally cannot happen), this strategy
 * MUST retry on that specific exception, or admitted requests fail outright
 * and the actual success count comes in under the true capacity -- exactly
 * what a first real run of this code caught: 27 successes instead of 50
 * under 200 concurrent threads, because failed attempts released their
 * counter slot but nothing was left to consume it.
 *
 * Reconciliation strategy (deliberately simple, matching this project's
 * scope): the counter lazily adopts MySQL's live AVAILABLE count the first
 * time it's touched, so a cold or lost Redis key re-syncs to ground truth
 * instead of starting at 0. If a retry attempt is genuinely exhausted or the
 * MySQL write fails for some other reason, the reservation is compensated
 * by incrementing the counter back. A production system would likely also
 * run a periodic job comparing the counter against MySQL's true count to
 * catch drift from any failure this compensation doesn't cover (e.g. the
 * JVM crashing mid-retry) -- not implemented here, since demonstrating the
 * locking tradeoff is this project's scope, not building a full
 * reconciliation pipeline.
 */
@Service
@ConditionalOnProperty(name = "app.booking.strategy", havingValue = "redis-counter")
public class RedisAtomicCounterBookingService implements BookingService {

    private static final int MAX_ATTEMPTS = 5;
    private static final long BASE_BACKOFF_MILLIS = 20;

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final OptimisticSeatBooker seatBooker;
    private final RedisAvailabilityCounter availabilityCounter;

    public RedisAtomicCounterBookingService(EventRepository eventRepository,
                                             SeatRepository seatRepository,
                                             OptimisticSeatBooker seatBooker,
                                             RedisAvailabilityCounter availabilityCounter) {
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.seatBooker = seatBooker;
        this.availabilityCounter = availabilityCounter;
    }

    @Override
    public BookingResult book(Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new EventNotFoundException(eventId);
        }

        long currentAvailable = seatRepository.countByEventIdAndStatus(eventId, SeatStatus.AVAILABLE);
        boolean reserved = availabilityCounter.tryReserve(eventId, currentAvailable);
        if (!reserved) {
            throw new NoSeatsAvailableException(eventId);
        }

        OptimisticLockingFailureException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return seatBooker.attempt(eventId);
            } catch (OptimisticLockingFailureException ex) {
                // Another admitted request won the race for the same seat
                // row. The Redis reservation is still valid -- it just needs
                // a different seat, which the next attempt's fresh read
                // will see (the loser's row is now BOOKED and excluded).
                lastConflict = ex;
                if (attempt < MAX_ATTEMPTS) {
                    backoffWithJitter(eventId, attempt);
                }
            } catch (RuntimeException ex) {
                // Genuinely no seat, or something unexpected -- give the
                // slot back so the counter doesn't permanently under-report
                // availability because of this failure.
                availabilityCounter.release(eventId);
                throw ex;
            }
        }

        availabilityCounter.release(eventId);
        throw new BookingConflictException(eventId, lastConflict);
    }

    private void backoffWithJitter(Long eventId, int attempt) {
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
