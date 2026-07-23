package com.ticketbooking.service;

public class BookingConflictException extends RuntimeException {

    public BookingConflictException(Long eventId, Throwable cause) {
        super("Could not acquire a seat lock for event " + eventId + " in time", cause);
    }
}
