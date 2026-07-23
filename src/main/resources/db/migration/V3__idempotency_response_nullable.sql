-- Supports the "claim first, fill in later" pattern: a request claims an
-- idempotency key by inserting a row with response_body/status_code still
-- NULL, does the actual booking, then fills those columns in. A second,
-- concurrent request with the same key fails to claim it (primary key
-- violation) and instead polls this same row until it's filled in, then
-- replays it -- so only one of the two ever actually books a seat.
ALTER TABLE booking_idempotency
    MODIFY COLUMN response_body TEXT NULL,
    MODIFY COLUMN status_code INT NULL;
