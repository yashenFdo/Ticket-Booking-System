package com.ticketbooking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketbooking.domain.BookingIdempotencyRecord;
import com.ticketbooking.repository.BookingIdempotencyRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Orchestrates Idempotency-Key handling around BookingService: claim the
 * key first (an INSERT that fails with a unique-constraint violation if
 * someone already claimed it), do the booking, then fill in the stored
 * response. A concurrent duplicate -- the same key arriving before the
 * first request has finished -- loses the claim and instead polls the same
 * row until the winner fills it in, then replays that exact response. This
 * is what prevents two simultaneous identical requests from both slipping
 * through and creating two bookings.
 *
 * Deliberately does not use @Transactional here: each JpaRepository call
 * (save, findById) is already individually transactional via Spring Data's
 * own handling on SimpleJpaRepository, and wrapping tryClaim/completeAndStore
 * in this class's own @Transactional method would only matter if called via
 * `this` from another method in the same bean -- which would silently be
 * ignored by Spring's proxy-based AOP anyway (the same self-invocation
 * pitfall documented on OptimisticSeatBooker).
 */
@Component
public class IdempotentBookingHandler {

    private static final int POLL_ATTEMPTS = 20;
    private static final long POLL_INTERVAL_MILLIS = 150;

    private final BookingIdempotencyRepository idempotencyRepository;
    private final BookingService bookingService;
    private final ObjectMapper objectMapper;

    public IdempotentBookingHandler(BookingIdempotencyRepository idempotencyRepository,
                                     BookingService bookingService,
                                     ObjectMapper objectMapper) {
        this.idempotencyRepository = idempotencyRepository;
        this.bookingService = bookingService;
        this.objectMapper = objectMapper;
    }

    public ResponseEntity<String> book(String idempotencyKey, Long eventId) {
        if (!tryClaim(idempotencyKey)) {
            return awaitExistingResult(idempotencyKey, eventId);
        }

        try {
            BookingResult result = bookingService.book(eventId);
            String body = writeJson(Map.of(
                    "bookingId", result.bookingId(),
                    "eventId", result.eventId(),
                    "seatId", result.seatId()));
            completeAndStore(idempotencyKey, HttpStatus.CREATED.value(), body);
            return ResponseEntity.status(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(body);
        } catch (EventNotFoundException | NoSeatsAvailableException ex) {
            // Terminal outcomes: this project has no cancellations or
            // inventory changes, so "sold out" / "no such event" stays true
            // on any future retry with this key -- safe to cache and replay.
            HttpStatus status = (ex instanceof EventNotFoundException) ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
            String body = writeJson(Map.of("message", ex.getMessage()));
            completeAndStore(idempotencyKey, status.value(), body);
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
        } catch (RuntimeException ex) {
            // Transient failure (e.g. BookingConflictException from a lock
            // timeout or exhausted optimistic retries). Do NOT cache this --
            // release the claim so a genuine retry with the same key gets a
            // fresh attempt instead of being stuck replaying a stale timing
            // failure forever.
            idempotencyRepository.deleteById(idempotencyKey);
            throw ex;
        }
    }

    private boolean tryClaim(String idempotencyKey) {
        try {
            idempotencyRepository.save(new BookingIdempotencyRecord(idempotencyKey));
            return true;
        } catch (DataIntegrityViolationException ex) {
            return false;
        }
    }

    private void completeAndStore(String idempotencyKey, int statusCode, String body) {
        BookingIdempotencyRecord record = idempotencyRepository.findById(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Missing idempotency claim for key: " + idempotencyKey));
        record.complete(statusCode, body);
        idempotencyRepository.save(record);
    }

    private ResponseEntity<String> awaitExistingResult(String idempotencyKey, Long eventId) {
        for (int attempt = 0; attempt < POLL_ATTEMPTS; attempt++) {
            Optional<BookingIdempotencyRecord> existing = idempotencyRepository.findById(idempotencyKey);
            if (existing.isPresent() && existing.get().isComplete()) {
                BookingIdempotencyRecord record = existing.get();
                return ResponseEntity.status(record.getStatusCode())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(record.getResponseBody());
            }
            sleep(eventId);
        }
        // The original request never finished within our poll budget.
        // Telling the caller to retry is more honest than guessing.
        throw new BookingConflictException(eventId);
    }

    private void sleep(Long eventId) {
        try {
            Thread.sleep(POLL_INTERVAL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BookingConflictException(eventId, e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize idempotent response body", e);
        }
    }
}
