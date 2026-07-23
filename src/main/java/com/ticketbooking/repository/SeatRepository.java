package com.ticketbooking.repository;

import com.ticketbooking.domain.Seat;
import com.ticketbooking.domain.SeatStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    long countByEventIdAndStatus(Long eventId, SeatStatus status);

    // Deliberately unguarded read for the naive Phase 1 service: this is a
    // plain SELECT with no locking clause, so the row it returns can already
    // be stale by the time the caller writes back to it. Must NOT gain a
    // @Lock -- that would silently fix the intentionally-broken baseline.
    Optional<Seat> findFirstByEventIdAndStatusOrderBySeatNumberAsc(Long eventId, SeatStatus status);

    // SELECT ... FOR UPDATE on at most one row (Pageable caps it at 1; JPQL
    // has no LIMIT keyword). A second transaction racing for the same seat
    // blocks on this query until the first commits/rolls back instead of
    // reading a stale snapshot -- that's the actual fix Phase 3 is about.
    // The lock-timeout hint turns an indefinite wait into a bounded one
    // that surfaces as PessimisticLockingFailureException, not a hang.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT s FROM Seat s WHERE s.eventId = :eventId AND s.status = :status ORDER BY s.seatNumber ASC")
    List<Seat> lockNextAvailableSeat(@Param("eventId") Long eventId, @Param("status") SeatStatus status, Pageable pageable);
}