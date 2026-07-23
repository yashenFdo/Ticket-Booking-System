package com.ticketbooking.repository;

import com.ticketbooking.domain.Seat;
import com.ticketbooking.domain.SeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    long countByEventIdAndStatus(Long eventId, SeatStatus status);

    // Deliberately unguarded read for the naive Phase 1 service: this is a
    // plain SELECT with no locking clause, so the row it returns can already
    // be stale by the time the caller writes back to it.
    Optional<Seat> findFirstByEventIdAndStatusOrderBySeatNumberAsc(Long eventId, SeatStatus status);
}