package com.ticketbooking.service;

/**
 * One strategy is active at a time, selected by app.booking.strategy
 * (see application.yml). This is what lets /api/bookings stay a single,
 * realistic endpoint while still letting each concurrency-control strategy
 * be benchmarked independently -- swap the property, restart, re-run k6.
 */
public interface BookingService {

    BookingResult book(Long eventId);
}
