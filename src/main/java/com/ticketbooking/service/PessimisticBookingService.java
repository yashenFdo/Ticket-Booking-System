package com.ticketbooking.service;

import com.ticketbooking.domain.Booking;
import com.ticketbooking.domain.Seat;
import com.ticketbooking.domain.SeatStatus;
import com.ticketbooking.repository.BookingRepository;
import com.ticketbooking.repository.EventRepository;
import com.ticketbooking.repository.SeatRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Fixes the Phase 1 race with a real row lock: SeatRepository.lockNextAvailableSeat
 * issues SELECT ... FOR UPDATE, so a second transaction racing for the same
 * seat blocks until the first commits, instead of reading a stale snapshot.
 * The tradeoff is throughput -- lock waits serialize contending requests
 * instead of letting them all proceed optimistically. See BENCHMARKS.md for
 * the measured cost versus Phase 2's naive baseline.
 *
 * Active by default (app.booking.strategy unset or =pessimistic) since this
 * is the first strategy that is actually correct under concurrency.
 */
@Service
@ConditionalOnProperty(name = "app.booking.strategy", havingValue = "pessimistic", matchIfMissing = true)
public class PessimisticBookingService implements BookingService {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;
    private final Timer lockWaitTimer;

    public PessimisticBookingService(EventRepository eventRepository,
                                      SeatRepository seatRepository,
                                      BookingRepository bookingRepository,
                                      MeterRegistry meterRegistry) {
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.bookingRepository = bookingRepository;
        this.lockWaitTimer = Timer.builder("booking.lock.wait")
                .description("Time spent acquiring a seat lock before booking")
                .tag("strategy", "pessimistic")
                .register(meterRegistry);
    }

    @Override
    @Transactional
    public BookingResult book(Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new EventNotFoundException(eventId);
        }

        List<Seat> locked;
        Timer.Sample sample = Timer.start();
        try {
            locked = seatRepository.lockNextAvailableSeat(eventId, SeatStatus.AVAILABLE, PageRequest.of(0, 1));
        } catch (PessimisticLockingFailureException ex) {
            // The lock-timeout hint fired: some other request held this row
            // long enough that we gave up waiting. A 409 tells the client
            // to retry, rather than a 500 or an indefinite hang.
            throw new BookingConflictException(eventId, ex);
        } finally {
            sample.stop(lockWaitTimer);
        }

        if (locked.isEmpty()) {
            throw new NoSeatsAvailableException(eventId);
        }

        Seat seat = locked.get(0);
        seat.setStatus(SeatStatus.BOOKED);
        seatRepository.save(seat);

        Booking booking = bookingRepository.save(new Booking(eventId, seat.getId()));
        return new BookingResult(booking.getId(), eventId, seat.getId());
    }
}
