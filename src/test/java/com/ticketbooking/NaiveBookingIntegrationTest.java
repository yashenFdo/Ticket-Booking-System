package com.ticketbooking;

import com.ticketbooking.api.dto.AuditResponse;
import com.ticketbooking.api.dto.BookingRequest;
import com.ticketbooking.api.dto.BookingResponse;
import com.ticketbooking.domain.Event;
import com.ticketbooking.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Single-threaded sanity checks only. This class does NOT assert anything
// about concurrent correctness -- that is Phase 2 (k6, proving the oversell)
// and Phase 3 (the 200-thread CountDownLatch test against the fixed
// implementation). Asserting "no oversell" here would be meaningless: a
// single-threaded caller can never race with itself.
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NaiveBookingIntegrationTest {

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

    private Long seededEventId() {
        List<Event> events = eventRepository.findAll();
        return events.get(0).getId();
    }

    @Test
    void bookingReducesAvailabilityByOne() {
        Long eventId = seededEventId();

        ResponseEntity<BookingResponse> bookingResponse = restTemplate.postForEntity(
                "/api/bookings", new BookingRequest(eventId), BookingResponse.class);

        assertThat(bookingResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(bookingResponse.getBody()).isNotNull();
        assertThat(bookingResponse.getBody().eventId()).isEqualTo(eventId);

        AuditResponse audit = restTemplate.getForObject(
                "/api/events/" + eventId + "/audit", AuditResponse.class);

        assertThat(audit.bookingsCreated()).isEqualTo(1);
        assertThat(audit.distinctSeatsBooked()).isEqualTo(1);
        assertThat(audit.oversoldBy()).isZero();
        assertThat(audit.duplicateSeatAssignments()).isZero();
    }

    @Test
    void bookingUnknownEventReturnsNotFound() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/bookings", new BookingRequest(999999L), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
