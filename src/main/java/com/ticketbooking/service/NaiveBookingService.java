package com.ticketbooking.service;

import com.ticketbooking.domain.Booking;
import com.ticketbooking.domain.Seat;
import com.ticketbooking.domain.SeatStatus;
import com.ticketbooking.repository.BookingRepository;
import com.ticketbooking.repository.EventRepository;
import com.ticketbooking.repository.SeatRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * INTENTIONALLY RACY BASELINE -- DO NOT USE THIS PATTERN OUTSIDE THIS DEMO.
 *
 * This is the naive read-check-write implementation the whole project
 * exists to disprove. It checks availability, then separately picks a seat
 * and writes to it, with no lock and no re-check between the two steps.
 * Wrapping the method in @Transactional does NOT fix this: MySQL's default
 * REPEATABLE READ isolation does not stop two concurrent transactions from
 * both reading the same seat as AVAILABLE and both writing to it -- InnoDB
 * only serializes writers on locking reads (SELECT ... FOR UPDATE) or
 * unique-constraint checks, and this method deliberately uses neither.
 *
 * Phase 3 replaces this with pessimistic locking, Phase 4 with optimistic
 * locking + retry, and Phase 5 with a Redis-based approach. This class stays
 * in the codebase afterwards, unused by any route, purely as the baseline
 * the benchmark table measures everything else against.
 *
 * Only active when app.booking.strategy=naive is set explicitly -- the
 * broken strategy is never the default.
 */
@Service
@ConditionalOnProperty(name = "app.booking.strategy", havingValue = "naive")
public class NaiveBookingService implements BookingService {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;

    public NaiveBookingService(EventRepository eventRepository,
                                SeatRepository seatRepository,
                                BookingRepository bookingRepository) {
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.bookingRepository = bookingRepository;
    }

    @Override
    @Transactional
    public BookingResult book(Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new EventNotFoundException(eventId);
        }

        // BUG (by design): this count can be stale the instant it is read.
        // Any number of concurrent callers can observe availableCount > 0
        // using the same pre-write snapshot of the seats table.
        long availableCount = seatRepository.countByEventIdAndStatus(eventId, SeatStatus.AVAILABLE);
        if (availableCount <= 0) {
            throw new NoSeatsAvailableException(eventId);
        }

        Seat seat = seatRepository.findFirstByEventIdAndStatusOrderBySeatNumberAsc(eventId, SeatStatus.AVAILABLE)
                .orElseThrow(() -> new NoSeatsAvailableException(eventId));

        // BUG (by design): blind write. No "WHERE status = AVAILABLE" guard,
        // no @Version check, no row lock -- so two requests that both read
        // this seat as AVAILABLE both reach here and both "succeed".
        seat.setStatus(SeatStatus.BOOKED);
        seatRepository.save(seat);

        Booking booking = bookingRepository.save(new Booking(eventId, seat.getId()));
        return new BookingResult(booking.getId(), eventId, seat.getId());
    }
}
