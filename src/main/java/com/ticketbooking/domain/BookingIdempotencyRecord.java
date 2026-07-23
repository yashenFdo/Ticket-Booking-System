package com.ticketbooking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

// Implements Persistable because idempotencyKey is an application-assigned
// (not auto-generated) @Id. Without this, Spring Data JPA's default
// isNew() check ("is the ID null?") would say false for a freshly
// constructed instance (its ID is already set), so repository.save() would
// call entityManager.merge() instead of persist(). merge() does
// SELECT-then-insert-or-update -- it would silently overwrite an existing
// row for the same key instead of failing with a constraint violation,
// which would break the entire "claim this key first" mechanism that
// IdempotentBookingHandler relies on to detect a concurrent duplicate.
@Entity
@Table(name = "booking_idempotency")
public class BookingIdempotencyRecord implements Persistable<String> {

    @Id
    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    protected BookingIdempotencyRecord() {
        // for JPA
    }

    public BookingIdempotencyRecord(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    @Override
    public String getId() {
        return idempotencyKey;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public boolean isComplete() {
        return responseBody != null && statusCode != null;
    }

    public void complete(int statusCode, String responseBody) {
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }
}
