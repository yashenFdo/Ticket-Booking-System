package com.ticketbooking.api.dto;

public record AuditResponse(
        Long eventId,
        int capacity,
        long bookingsCreated,
        long distinctSeatsBooked,
        long oversoldBy,
        long duplicateSeatAssignments
) {
}
