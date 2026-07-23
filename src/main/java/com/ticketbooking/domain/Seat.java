package com.ticketbooking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

// The `version` column has existed in the schema since V1 (see
// V1__init_schema.sql) but was only mapped starting Phase 4, where
// OptimisticBookingService relies on it. @Version is a cross-cutting,
// table-wide concern in Hibernate -- it is not possible to map it "only for
// one service" while others share the same entity/table. That means from
// this point on, re-running NaiveBookingService or PessimisticBookingService
// under real concurrency will also incidentally trigger version checks on
// this entity; their Phase 1-3 benchmark numbers were captured (or are
// meant to be captured) from the tagged commits before this change
// (phase-1-naive-baseline, phase-3-pessimistic-locking), not from HEAD.
@Entity
@Table(name = "seats", uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "seat_number"}))
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "seat_number", nullable = false)
    private int seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatStatus status;

    @Version
    @Column(nullable = false)
    private Long version;

    protected Seat() {
        // for JPA
    }

    public Seat(Long eventId, int seatNumber, SeatStatus status) {
        this.eventId = eventId;
        this.seatNumber = seatNumber;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public Long getEventId() {
        return eventId;
    }

    public int getSeatNumber() {
        return seatNumber;
    }

    public SeatStatus getStatus() {
        return status;
    }

    public void setStatus(SeatStatus status) {
        this.status = status;
    }

    public Long getVersion() {
        return version;
    }
}
