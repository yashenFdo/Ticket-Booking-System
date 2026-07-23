package com.ticketbooking;

import com.ticketbooking.api.dto.BookingRequest;
import com.ticketbooking.domain.Event;
import com.ticketbooking.domain.Seat;
import com.ticketbooking.domain.SeatStatus;
import com.ticketbooking.repository.EventRepository;
import com.ticketbooking.repository.SeatRepository;
import com.ticketbooking.service.AuditResult;
import com.ticketbooking.service.AuditService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// Similar shape to PessimisticBookingConcurrencyTest, but with a
// deliberately different assertion. Pessimistic/Redis-lock GUARANTEE
// exactly `capacity` successes because a waiter never gives up, it just
// blocks. Optimistic locking's bounded retry (5 attempts) does NOT
// guarantee that -- under 200 threads hammering 50 seats, a first real run
// against real MySQL showed 48/50 succeeding: two requests genuinely
// exhausted their retry budget under contention even though seats existed,
// exactly the tradeoff documented in ADR-001 ("bounded retries can return
// 'no seat' even when inventory objectively still exists"). Asserting
// "exactly capacity" here would be asserting a guarantee this strategy does
// not make. The actual guarantee -- and what this test checks -- is safety
// (never oversold, never duplicated), not liveness under extreme contention.

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "app.booking.strategy=optimistic")
class OptimisticBookingConcurrencyTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private AuditService auditService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void neverOversellsOrDuplicatesUnderTwoHundredConcurrentThreads() throws InterruptedException {
        int capacity = 50;
        int threadCount = 200;

        Event event = eventRepository.save(new Event("Optimistic Concurrency Test", capacity));
        for (int seatNumber = 1; seatNumber <= capacity; seatNumber++) {
            seatRepository.save(new Seat(event.getId(), seatNumber, SeatStatus.AVAILABLE));
        }

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    ResponseEntity<String> response = restTemplate.postForEntity(
                            "/api/bookings", new BookingRequest(event.getId()), String.class);
                    if (response.getStatusCode().is2xxSuccessful()) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).as("all 200 requests completed within 60s").isTrue();
        // Safety, not liveness: never more than capacity, and whatever DID
        // succeed must be reflected exactly in MySQL with no duplicates.
        assertThat(successCount.get()).isPositive().isLessThanOrEqualTo(capacity);

        AuditResult audit = auditService.audit(event.getId());
        assertThat(audit.bookingsCreated()).isEqualTo(successCount.get());
        assertThat(audit.oversoldBy()).isZero();
        assertThat(audit.duplicateSeatAssignments()).isZero();

        // 200 threads for 50 seats guarantees version conflicts, so the
        // retry counter must have moved -- if it's still zero, the retry
        // path never actually engaged and this test isn't proving anything.
        double retries = meterRegistry.get("booking.optimistic.retry.attempts").counter().count();
        assertThat(retries).isGreaterThan(0);
    }
}
