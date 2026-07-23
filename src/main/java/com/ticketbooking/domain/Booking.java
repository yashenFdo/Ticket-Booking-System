package com.ticketbooking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    // Not unique yet -- see V1__init_schema.sql. The naive Phase 1 service
    // can legitimately create more than one Booking row for the same seat.
    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected Booking() {
        // for JPA
    }

    public Booking(Long eventId, Long seatId) {
        this.eventId = eventId;
        this.seatId = seatId;
    }

    public Long getId() {
        return id;
    }

    public Long getEventId() {
        return eventId;
    }

    public Long getSeatId() {
        return seatId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}