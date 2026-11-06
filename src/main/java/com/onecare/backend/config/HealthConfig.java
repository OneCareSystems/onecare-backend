package com.onecare.backend.config;

import org.springframework.boot.actuate.health.SimpleStatusAggregator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.actuate.health.StatusAggregator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Health aggregation policy (DDP-#49, AC4 / CON007 risk R17).
 *
 * <p>Adds a custom {@code DEGRADED} status ordered between DOWN and UP. When an
 * optional integration (currently SMTP) is unreachable, the aggregate health
 * reports DEGRADED — which the actuator maps to HTTP 200, so the docker-compose
 * healthcheck keeps the container "healthy" and core clinic flows stay
 * available. A MySQL or Redis failure still aggregates to DOWN (HTTP 503,
 * container unhealthy): only those two gate the stack (SDS 2.5.1).
 *
 * <p>Severity order, worst first: OUT_OF_SERVICE &gt; DOWN &gt; DEGRADED &gt; UP.
 * (Spring Boot 3.5 replaced OrderedHealthAggregator/HealthStatusAggregator
 * with StatusAggregator/SimpleStatusAggregator.)
 */
@Configuration(proxyBeanMethods = false)
public class HealthConfig {

    /** Custom health status: optional integration unavailable, core stack fine. */
    public static final Status DEGRADED = new Status("DEGRADED");

    @Bean
    public StatusAggregator statusAggregator() {
        return new SimpleStatusAggregator(
                Status.OUT_OF_SERVICE, Status.DOWN, DEGRADED, Status.UP);
    }
}
