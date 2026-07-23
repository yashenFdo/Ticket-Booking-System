INSERT INTO events (name, total_seats) VALUES ('Concurrency Test Event', 1000);

-- Recursive CTE generates seat_number 1..1000. MySQL 8's default
-- cte_max_recursion_depth (1000) covers this without raising the limit.
INSERT INTO seats (event_id, seat_number, status)
WITH RECURSIVE seat_numbers AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seat_numbers WHERE n < 1000
)
SELECT
    (SELECT id FROM events WHERE name = 'Concurrency Test Event'),
    n,
    'AVAILABLE'
FROM seat_numbers;