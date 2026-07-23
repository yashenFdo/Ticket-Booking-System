package com.ticketbooking;

import com.ticketbooking.api.dto.BookingRequest;
import com.ticketbooking.domain.Event;
import com.ticketbooking.domain.Seat;
import com.ticketbooking.domain.SeatStatus;
import com.ticketbooking.repository.EventRepository;
import com.ticketbooking.repository.SeatRepository;
import com.ticketbooking.service.AuditResult;
import com.ticketbooking.service.AuditService;
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

// This is the hard rule #2 test: a fix ships with a test that fails against
// the previous implementation. Point app.booking.strategy at "naive" (Phase
// 1) instead of "pessimistic" and this test fails -- more than 50 of the 200
// threads succeed, because the naive service has no lock to serialize on.
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "app.booking.strategy=pessimistic")
class PessimisticBookingConcurrencyTest {

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

    @Test
    void exactlyCapacitySeatsSucceedUnderTwoHundredConcurrentThreads() throws InterruptedException {
        int capacity = 50;
        int threadCount = 200;

        // A dedicated small event, separate from the 1000-seat seed data,
        // so this test's contention ratio (200 threads : 50 seats) is exact
        // and doesn't depend on how much of the seeded event is already
        // booked by other tests sharing this container.
        Event event = eventRepository.save(new Event("Pessimistic Concurrency Test", capacity));
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

        // Every thread is submitted and blocked on the latch before any of
        // them is released, so they all hit the lock at effectively the
        // same instant instead of trickling in.
        startLatch.countDown();
        boolean finished = doneLatch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).as("all 200 requests completed within 60s").isTrue();
        assertThat(successCount.get()).isEqualTo(capacity);

        AuditResult audit = auditService.audit(event.getId());
        assertThat(audit.bookingsCreated()).isEqualTo(capacity);
        assertThat(audit.oversoldBy()).isZero();
        assertThat(audit.duplicateSeatAssignments()).isZero();
    }
}
