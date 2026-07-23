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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// The Redis gate admits at most `capacity` requests -- but unlike
// pessimistic/redis-lock, admission is not the same as success. All
// admitted requests then race for the same seat rows via
// OptimisticSeatBooker's bounded retry, and a first real run showed only
// 38/50 succeeding: this strategy concentrates ALL real contention into one
// simultaneous N-way scrum among the admitted requests (unlike pure
// optimistic locking, where most of 200 requests fail the early count
// check rather than the seat-write race, spreading contention out). Worth
// remembering when reading BENCHMARKS.md: decoupling admission control from
// seat assignment trades a fast/cheap gate for a harder DB-level race among
// whoever gets through it. The guarantee this test checks is safety (never
// oversold, never duplicated), not that every admitted request wins.
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "app.booking.strategy=redis-counter")
class RedisCounterBookingConcurrencyTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
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
    void neverOversellsOrDuplicatesUnderTwoHundredConcurrentThreads() throws InterruptedException {
        int capacity = 50;
        int threadCount = 200;

        Event event = eventRepository.save(new Event("Redis Counter Concurrency Test", capacity));
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
        // Safety, not liveness -- see the class comment.
        assertThat(successCount.get()).isPositive().isLessThanOrEqualTo(capacity);

        AuditResult audit = auditService.audit(event.getId());
        assertThat(audit.bookingsCreated()).isEqualTo(successCount.get());
        assertThat(audit.oversoldBy()).isZero();
        assertThat(audit.duplicateSeatAssignments()).isZero();
    }
}
