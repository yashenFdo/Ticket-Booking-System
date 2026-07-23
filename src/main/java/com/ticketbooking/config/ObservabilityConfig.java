package com.ticketbooking.config;

import com.ticketbooking.service.AuditService;
import com.ticketbooking.service.EventNotFoundException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * Registers booking.oversold: a live gauge, sampled on every Prometheus
 * scrape, reporting AuditService's oversoldBy for one tracked event (the
 * seed migration's single event by default -- see app.metrics.tracked-event-id).
 * This is the metric the whole project is trying to keep pinned at 0 from
 * Phase 3 onward; a graph of this that ever leaves zero is the project's
 * core claim failing.
 */
@Configuration
public class ObservabilityConfig {

    private final MeterRegistry meterRegistry;
    private final AuditService auditService;
    private final long trackedEventId;

    public ObservabilityConfig(MeterRegistry meterRegistry,
                                AuditService auditService,
                                @Value("${app.metrics.tracked-event-id:1}") long trackedEventId) {
        this.meterRegistry = meterRegistry;
        this.auditService = auditService;
        this.trackedEventId = trackedEventId;
    }

    @PostConstruct
    void registerOversoldGauge() {
        Gauge.builder("booking.oversold", this, ObservabilityConfig::currentOversoldBy)
                .description("oversoldBy for the tracked event; must stay 0 from Phase 3 onward")
                .register(meterRegistry);
    }

    private double currentOversoldBy() {
        try {
            return auditService.audit(trackedEventId).oversoldBy();
        } catch (EventNotFoundException e) {
            // Tracked event doesn't exist yet (e.g. a test context with no
            // seed data) -- report 0 rather than failing the scrape.
            return 0.0;
        }
    }
}
