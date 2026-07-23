package com.ticketbooking.service;

import com.ticketbooking.domain.Event;
import com.ticketbooking.repository.BookingRepository;
import com.ticketbooking.repository.EventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns "it feels broken" into a number. This is the correctness verifier
 * every phase is checked against -- oversoldBy must be 0 from Phase 3
 * onward, and this service is what proves it, not a visual inspection.
 */
@Service
@Transactional(readOnly = true)
public class AuditService {

    private final EventRepository eventRepository;
    private final BookingRepository bookingRepository;

    public AuditService(EventRepository eventRepository, BookingRepository bookingRepository) {
        this.eventRepository = eventRepository;
        this.bookingRepository = bookingRepository;
    }

    public AuditResult audit(Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new EventNotFoundException(eventId));

        long bookingsCreated = bookingRepository.countByEventId(eventId);
        long distinctSeatsBooked = bookingRepository.countDistinctSeatsByEventId(eventId);
        long oversoldBy = Math.max(0, bookingsCreated - event.getTotalSeats());
        long duplicateSeatAssignments = bookingsCreated - distinctSeatsBooked;

        return new AuditResult(event.getTotalSeats(), bookingsCreated, distinctSeatsBooked,
                oversoldBy, duplicateSeatAssignments);
    }
}
