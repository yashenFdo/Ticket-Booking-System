package com.ticketbooking.api;

import com.ticketbooking.api.dto.BookingRequest;
import com.ticketbooking.api.dto.BookingResponse;
import com.ticketbooking.service.BookingResult;
import com.ticketbooking.service.BookingService;
import com.ticketbooking.service.IdempotentBookingHandler;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final BookingService bookingService;
    private final IdempotentBookingHandler idempotentBookingHandler;

    public BookingController(BookingService bookingService, IdempotentBookingHandler idempotentBookingHandler) {
        this.bookingService = bookingService;
        this.idempotentBookingHandler = idempotentBookingHandler;
    }

    @PostMapping
    public ResponseEntity<?> book(@Valid @RequestBody BookingRequest request,
                                   @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return idempotentBookingHandler.book(idempotencyKey, request.eventId());
        }

        BookingResult result = bookingService.book(request.eventId());
        BookingResponse response = new BookingResponse(result.bookingId(), result.eventId(), result.seatId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
