package com.onecare.backend.integration;

import com.onecare.backend.entity.User;
import com.onecare.backend.enums.Role;
import com.onecare.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC4 (integration): SMTP unreachable => the stack still starts, /actuator/health
 * reports DEGRADED (HTTP 200, not DOWN/503), and core clinic flows keep working.
 *
 * <p>The SMTP target is 127.0.0.1:1 where no server listens, so every probe is
 * refused — exactly the "SMTP gateway down" scenario (CON007 risk R17).
 * Redis is the in-memory test double; MySQL is H2.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // Nothing listens on this port: the SMTP probe must fail fast (AC4).
        "spring.mail.host=127.0.0.1",
        "spring.mail.port=1",
        "management.health.smtp.connect-timeout-ms=300",
        // Spring's built-in mail indicator would report DOWN and drag the
        // aggregate to 503; the custom smtp indicator supersedes it.
        "management.health.mail.enabled=false",
        // The mocked RedisConnectionFactory has no live connection to ping;
        // Redis' own indicator would otherwise report DOWN in tests.
        "management.health.redis.enabled=false",
        "management.endpoint.health.show-details=always"
})
class HealthDegradedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void createUser() {
        // Same upsert pattern as JwtAuthenticationIntegrationTest: audit_log
        // has an FK on users, so deleteAll() would violate it.
        User user = userRepository.findByUsername("ac4-doctor").orElseGet(User::new);
        user.setUsername("ac4-doctor");
        user.setEmail("ac4@example.com");
        user.setPasswordHash(passwordEncoder.encode("Password@123"));
        user.setRole(Role.DOCTOR);
        user.setIsActive(true);
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
    }

    @Test
    void healthReportsDegradedNotDownWhenSmtpIsUnreachable() throws Exception {
        String body = mockMvc.perform(get("/actuator/health"))
                // DEGRADED maps to HTTP 200: the container must stay "healthy".
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.components.smtp.status").value("DEGRADED"))
                // Only MySQL (and Redis) gate the stack: db must be UP.
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // The degraded detail is visible for ops, with no secrets in it.
        assertThat(body).contains("SMTP gateway unreachable");
        assertThat(body).doesNotContain("password");
    }

    @Test
    void coreClinicFlowStillWorksWhileSmtpIsDown() throws Exception {
        // Login is a core clinic flow that depends on DB + JWT, not on SMTP.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ac4-doctor","password":"Password@123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }

    @Test
    void publicBookingStillValidatesWhileSmtpIsDown() throws Exception {
        // A validation rejection (400) proves routing, the security filter chain
        // and the controller are alive; only SMTP is degraded. A dead stack 5xx.
        mockMvc.perform(post("/api/public/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void healthEndpointIsReachableWithoutAuthentication() throws Exception {
        // Compose healthchecks and LB probes carry no JWT; this stays public.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void otherActuatorEndpointsStayUnexposed() throws Exception {
        // Only /actuator/health is exposed AND public. /actuator/env is not in
        // management.endpoints.web.exposure.include, and the security chain
        // rejects it anonymously (401) before it can 404 — either way it is
        // unreachable without credentials (AC2 surface check).
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isUnauthorized());
    }
}
