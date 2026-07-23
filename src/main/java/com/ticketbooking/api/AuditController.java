package com.ticketbooking.api;

import com.ticketbooking.api.dto.AuditResponse;
import com.ticketbooking.service.AuditResult;
import com.ticketbooking.service.AuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/{id}/audit")
    public AuditResponse audit(@PathVariable("id") Long eventId) {
        AuditResult result = auditService.audit(eventId);
        return new AuditResponse(eventId, result.capacity(), result.bookingsCreated(),
                result.distinctSeatsBooked(), result.oversoldBy(), result.duplicateSeatAssignments());
    }
}
