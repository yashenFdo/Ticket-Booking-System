package com.ticketbooking.api.dto;

import jakarta.validation.constraints.NotNull;

public record BookingRequest(@NotNull Long eventId) {
}
