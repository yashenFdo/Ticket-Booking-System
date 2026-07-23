package com.ticketbooking.service;

public class NoSeatsAvailableException extends RuntimeException {

    public NoSeatsAvailableException(Long eventId) {
        super("No seats available for event: " + eventId);
    }
}
