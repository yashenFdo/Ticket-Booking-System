package com.ticketbooking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "total_seats", nullable = false)
    private int totalSeats;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected Event() {
        // for JPA
    }

    public Event(String name, int totalSeats) {
        this.name = name;
        this.totalSeats = totalSeats;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getTotalSeats() {
        return totalSeats;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}