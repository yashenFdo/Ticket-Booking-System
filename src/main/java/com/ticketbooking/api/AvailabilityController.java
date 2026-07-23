package com.ticketbooking.api;

import com.ticketbooking.api.dto.AvailabilityResponse;
import com.ticketbooking.service.AvailabilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
public class AvailabilityController {

    private final AvailabilityService availabilityService;

    public AvailabilityController(AvailabilityService availabilityService) {
        this.availabilityService = availabilityService;
    }

    @GetMapping("/{id}/availability")
    public AvailabilityResponse getAvailability(@PathVariable("id") Long eventId) {
        long available = availabilityService.getAvailableSeatCount(eventId);
        return new AvailabilityResponse(eventId, available);
    }
}
