package com.ticketbooking.service;

import com.ticketbooking.domain.Booking;
import com.ticketbooking.domain.Seat;
import com.ticketbooking.domain.SeatStatus;
import com.ticketbooking.repository.BookingRepository;
import com.ticketbooking.repository.SeatRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A single transactional attempt at booking a seat, split out into its own
 * bean specifically so it goes through Spring's transactional proxy. If
 * OptimisticBookingService called this logic via a private/self method
 * instead, @Transactional would be silently ignored (self-invocation
 * bypasses the proxy) and the seat update + booking insert could commit as
 * two separate implicit transactions instead of one atomic unit.
 */
@Component
class OptimisticSeatBooker {

    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;

    OptimisticSeatBooker(SeatRepository seatRepository, BookingRepository bookingRepository) {
        this.seatRepository = seatRepository;
        this.bookingRepository = bookingRepository;
    }

    @Transactional
    BookingResult attempt(Long eventId) {
        // Same unguarded read the naive service uses -- optimistic locking
        // does not avoid the stale read, it relies entirely on the @Version
        // check at write time to detect that the read was stale.
        Seat seat = seatRepository.findFirstByEventIdAndStatusOrderBySeatNumberAsc(eventId, SeatStatus.AVAILABLE)
                .orElseThrow(() -> new NoSeatsAvailableException(eventId));

        seat.setStatus(SeatStatus.BOOKED);
        // Hibernate emits UPDATE ... WHERE id = ? AND version = ? here (at
        // flush/commit). If another transaction already bumped the version,
        // zero rows match and Hibernate throws OptimisticLockingFailureException
        // instead of silently overwriting.
        seatRepository.save(seat);

        Booking booking = bookingRepository.save(new Booking(eventId, seat.getId()));
        return new BookingResult(booking.getId(), eventId, seat.getId());
    }
}
