CREATE TABLE events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    total_seats INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE seats (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id BIGINT NOT NULL,
    seat_number INT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    -- Unused until Phase 4 (optimistic locking). Added now so the schema
    -- never needs a breaking migration later; the JPA entity does not map
    -- this column until Phase 4, so it has no effect on Phases 0-3.
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_seats_event FOREIGN KEY (event_id) REFERENCES events (id),
    CONSTRAINT uq_seats_event_number UNIQUE (event_id, seat_number)
);

CREATE TABLE bookings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id BIGINT NOT NULL,
    -- A seat can back at most one booking row. This only guarantees no two
    -- bookings point at the same seat; it does NOT guarantee the total
    -- booking count stays under capacity -- that guarantee is exactly what
    -- Phase 1's naive implementation is missing, and what Phases 3-5 add.
    seat_id BIGINT NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_bookings_event FOREIGN KEY (event_id) REFERENCES events (id),
    CONSTRAINT fk_bookings_seat FOREIGN KEY (seat_id) REFERENCES seats (id)
);

-- Unused until Phase 6 (idempotency keys). Created now to avoid a later
-- migration purely for scaffolding.
CREATE TABLE booking_idempotency (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    response_body TEXT NOT NULL,
    status_code INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);