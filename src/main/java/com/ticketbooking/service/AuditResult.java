package com.ticketbooking.service;

public record AuditResult(
        int capacity,
        long bookingsCreated,
        long distinctSeatsBooked,
        long oversoldBy,
        long duplicateSeatAssignments
) {
}
