package com.ticketbooking.service;

import com.ticketbooking.domain.SeatStatus;
import com.ticketbooking.repository.EventRepository;
import com.ticketbooking.repository.SeatRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * The alternative to a distributed lock: instead of serializing access to
 * MySQL, gate entry with an atomic Redis counter (decrement_if_available.lua)
 * and only touch MySQL once a slot has actually been reserved. MySQL stays
 * the system of record for which specific seat got booked and for the
 * /audit endpoint; Redis is purely the fast admission-control layer.
 *
 * Reconciliation strategy (deliberately simple, matching this project's
 * scope): the counter lazily adopts MySQL's live AVAILABLE count the first
 * time it's touched, so a cold or lost Redis key re-syncs to ground truth
 * instead of starting at 0. If the MySQL write fails after Redis already
 * granted a slot (the classic dual-write problem -- these two systems can't
 * be updated atomically together), the reservation is compensated by
 * incrementing the counter back. A production system would likely also run
 * a periodic job comparing the counter against MySQL's true count to catch
 * drift from any failure this compensation doesn't cover (e.g. the JVM
 * crashing between the Redis decrement and the compensating release) --
 * not implemented here, since demonstrating the locking tradeoff is this
 * project's scope, not building a full reconciliation pipeline.
 */
@Service
@ConditionalOnProperty(name = "app.booking.strategy", havingValue = "redis-counter")
public class RedisAtomicCounterBookingService implements BookingService {

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

        try {
            return seatBooker.attempt(eventId);
        } catch (RuntimeException ex) {
            // Redis granted a slot but the MySQL write didn't complete --
            // give the slot back so the counter doesn't permanently
            // under-report availability because of this failure.
            availabilityCounter.release(eventId);
            throw ex;
        }
    }
}
