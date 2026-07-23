package com.ticketbooking.repository;

import com.ticketbooking.domain.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    long countByEventId(Long eventId);

    @Query("SELECT COUNT(DISTINCT b.seatId) FROM Booking b WHERE b.eventId = :eventId")
    long countDistinctSeatsByEventId(@Param("eventId") Long eventId);
}
