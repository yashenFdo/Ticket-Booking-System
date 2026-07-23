package com.ticketbooking.repository;

import com.ticketbooking.domain.Seat;
import com.ticketbooking.domain.SeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    long countByEventIdAndStatus(Long eventId, SeatStatus status);
}