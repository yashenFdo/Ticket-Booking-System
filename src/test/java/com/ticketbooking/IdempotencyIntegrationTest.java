package com.ticketbooking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketbooking.api.dto.BookingRequest;
import com.ticketbooking.domain.Event;
import com.ticketbooking.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotencyIntegrationTest {

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
    private ObjectMapper objectMapper;

    private Long seededEventId() {
        List<Event> events = eventRepository.findAll();
        return events.get(0).getId();
    }

    private HttpEntity<BookingRequest> requestWithKey(Long eventId, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        return new HttpEntity<>(new BookingRequest(eventId), headers);
    }

    private long extractBookingId(String json) {
        try {
            return objectMapper.readTree(json).get("bookingId").asLong();
        } catch (Exception e) {
            throw new RuntimeException("Could not parse bookingId from: " + json, e);
        }
    }

    @Test
    void replayWithSameKeyReturnsTheOriginalResponse() {
        Long eventId = seededEventId();
        String key = "sequential-replay-" + UUID.randomUUID();
        HttpEntity<BookingRequest> entity = requestWithKey(eventId, key);

        ResponseEntity<String> first = restTemplate.postForEntity("/api/bookings", entity, String.class);
        ResponseEntity<String> second = restTemplate.postForEntity("/api/bookings", entity, String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // If the replay had created a second booking, its bookingId would
        // differ from the first (auto-increment), so an identical body here
        // is direct proof no second booking happened.
        assertThat(second.getStatusCode()).isEqualTo(first.getStatusCode());
        assertThat(second.getBody()).isEqualTo(first.getBody());
    }

    @Test
    void concurrentRequestsWithSameKeyCreateExactlyOneBooking() throws InterruptedException {
        Long eventId = seededEventId();
        String key = "concurrent-duplicate-" + UUID.randomUUID();
        HttpEntity<BookingRequest> entity = requestWithKey(eventId, key);

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<ResponseEntity<String>> responses = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    responses.add(restTemplate.postForEntity("/api/bookings", entity, String.class));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).as("all requests completed within 30s").isTrue();
        assertThat(responses).hasSize(threadCount);
        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED));

        // The real assertion: however many of the 20 concurrent requests
        // raced for this one key, exactly one underlying booking exists.
        var distinctBookingIds = responses.stream()
                .map(r -> extractBookingId(r.getBody()))
                .collect(Collectors.toSet());
        assertThat(distinctBookingIds).hasSize(1);
    }
}
