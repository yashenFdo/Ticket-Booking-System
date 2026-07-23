package com.ticketbooking.repository;

import com.ticketbooking.domain.BookingIdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingIdempotencyRepository extends JpaRepository<BookingIdempotencyRecord, String> {
}
