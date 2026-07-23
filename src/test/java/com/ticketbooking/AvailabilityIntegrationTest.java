package com.ticketbooking;

import com.ticketbooking.api.dto.AvailabilityResponse;
import com.ticketbooking.domain.Event;
import com.ticketbooking.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Real MySQL via Testcontainers, never H2: Flyway's V1 schema and the
// recursive-CTE seed in V2 are MySQL-specific, and later phases depend on
// row-locking behaviour (SELECT ... FOR UPDATE, isolation levels) that H2
// does not faithfully replicate.
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AvailabilityIntegrationTest {

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

    @Test
    void seededEventHasOneThousandAvailableSeats() {
        List<Event> events = eventRepository.findAll();
        assertThat(events).hasSize(1);
        Long eventId = events.get(0).getId();

        AvailabilityResponse response = restTemplate.getForObject(
                "/api/events/" + eventId + "/availability",
                AvailabilityResponse.class);

        assertThat(response.availableSeats()).isEqualTo(1000);
    }

    @Test
    void unknownEventReturnsNotFound() {
        var response = restTemplate.getForEntity(
                "/api/events/999999/availability",
                String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
