package com.ticketbooking.api;

import com.ticketbooking.api.dto.BookingRequest;
import com.ticketbooking.api.dto.BookingResponse;
import com.ticketbooking.service.BookingResult;
import com.ticketbooking.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    public ResponseEntity<BookingResponse> book(@Valid @RequestBody BookingRequest request) {
        BookingResult result = bookingService.book(request.eventId());
        BookingResponse response = new BookingResponse(result.bookingId(), result.eventId(), result.seatId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
