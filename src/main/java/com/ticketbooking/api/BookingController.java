package com.ticketbooking.api;

import com.ticketbooking.api.dto.BookingRequest;
import com.ticketbooking.api.dto.BookingResponse;
import com.ticketbooking.service.BookingResult;
import com.ticketbooking.service.BookingService;
import com.ticketbooking.service.IdempotentBookingHandler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Supplier;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final BookingService bookingService;
    private final IdempotentBookingHandler idempotentBookingHandler;
    private final Timer bookingLatencyTimer;

    public BookingController(BookingService bookingService,
                              IdempotentBookingHandler idempotentBookingHandler,
                              MeterRegistry meterRegistry) {
        this.bookingService = bookingService;
        this.idempotentBookingHandler = idempotentBookingHandler;
        // End-to-end latency of this endpoint, regardless of which
        // BookingService strategy is active -- instrumented at the
        // controller boundary so it applies uniformly to all five
        // strategies without touching each of their implementations.
        this.bookingLatencyTimer = Timer.builder("booking.latency")
                .description("End-to-end latency of POST /api/bookings")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    @PostMapping
    public ResponseEntity<?> book(@Valid @RequestBody BookingRequest request,
                                   @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        // Explicitly typed rather than inline, so javac doesn't have to
        // infer a common generic type across two differently-parameterized
        // ResponseEntity return points inside a lambda passed to a generic
        // method -- that inference is fragile; an explicit Supplier<ResponseEntity<?>>
        // sidesteps it entirely.
        Supplier<ResponseEntity<?>> action = () -> {
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                return idempotentBookingHandler.book(idempotencyKey, request.eventId());
            }

            BookingResult result = bookingService.book(request.eventId());
            BookingResponse response = new BookingResponse(result.bookingId(), result.eventId(), result.seatId());
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        };

        // Timer.record(Supplier) measures wall time around the call and
        // still records it if the supplier throws (rethrown after), so
        // rejected bookings (404/409) count toward latency too, not just
        // successes.
        return bookingLatencyTimer.record(action);
    }
}
