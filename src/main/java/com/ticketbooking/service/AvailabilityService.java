package com.ticketbooking.service;

import com.ticketbooking.domain.SeatStatus;
import com.ticketbooking.repository.EventRepository;
import com.ticketbooking.repository.SeatRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AvailabilityService {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;

    public AvailabilityService(EventRepository eventRepository, SeatRepository seatRepository) {
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
    }

    public long getAvailableSeatCount(Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new EventNotFoundException(eventId);
        }
        return seatRepository.countByEventIdAndStatus(eventId, SeatStatus.AVAILABLE);
    }
}
