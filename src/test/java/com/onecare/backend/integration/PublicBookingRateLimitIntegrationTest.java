package com.onecare.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DDP-33 / AC2 / NFRS-009: repeated rapid submissions from the same IP to the
 * public-booking endpoint return 429, using the dedicated public-booking rate
 * limit (distinct from the /api/auth tier).
 */
@SpringBootTest(properties = {
        "public-booking.rate-limit.enabled=true",
        "public-booking.rate-limit.requests-per-minute=2",
        "public-booking.rate-limit.window-seconds=60",
        "api.rate-limit.trusted-proxies=127.0.0.1"
})
@AutoConfigureMockMvc
class PublicBookingRateLimitIntegrationTest {

    private static final String CLIENT_IP = "10.9.8.100";

    @Autowired
    private MockMvc mockMvc;

    /**
     * Body validity is irrelevant to the limiter (it runs before the controller);
     * an unknown doctorId makes the would-be-allowed requests fail fast with 404.
     */
    private void bookingAttempt(String ip) throws Exception {
        mockMvc.perform(post("/api/public/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Rate Limit Probe","contactNo":"0770000001",\
                                "dateOfBirth":"1990-01-01","doctorId":999999,\
                                "appointmentDateTime":"%s"}
                                """.formatted(LocalDateTime.now().plusDays(1)
                                        .withNano(0).withSecond(0)))
                        .header("X-Forwarded-For", ip));
    }

    @Test
    void repeatedRapidSubmissions_fromSameIp_return429() throws Exception {
        bookingAttempt(CLIENT_IP);
        bookingAttempt(CLIENT_IP);

        mockMvc.perform(post("/api/public/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Rate Limit Probe","contactNo":"0770000001",\
                                "dateOfBirth":"1990-01-01","doctorId":999999,\
                                "appointmentDateTime":"%s"}
                                """.formatted(LocalDateTime.now().plusDays(1)
                                        .withNano(0).withSecond(0)))
                        .header("X-Forwarded-For", CLIENT_IP))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", not(emptyString())))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Too many requests. Please try again later."));
    }

    @Test
    void publicBookingLimit_isDistinctFromAuthTier_forSameIp() throws Exception {
        // Exhaust the public-booking tier (2/min) for this IP...
        bookingAttempt(CLIENT_IP);
        bookingAttempt(CLIENT_IP);
        bookingAttempt(CLIENT_IP);

        // ...the auth tier for the same IP is untouched (still has its own 10/min)
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"unknown-for-rate-limit@onecare.com\"}")
                        .header("X-Forwarded-For", CLIENT_IP))
                .andExpect(status().isOk());
    }

    @Test
    void otherClientIps_areNotAffected() throws Exception {
        // Exhaust one client...
        bookingAttempt("10.9.8.110");
        bookingAttempt("10.9.8.110");
        bookingAttempt("10.9.8.110");

        // ...an unrelated client still gets a non-429 response
        mockMvc.perform(post("/api/public/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Rate Limit Probe","contactNo":"0770000002",\
                                "dateOfBirth":"1990-01-01","doctorId":999999,\
                                "appointmentDateTime":"%s"}
                                """.formatted(LocalDateTime.now().plusDays(1)
                                        .withNano(0).withSecond(0)))
                        .header("X-Forwarded-For", "10.9.8.111"))
                .andExpect(result -> org.assertj.core.api.Assertions
                        .assertThat(result.getResponse().getStatus()).isNotEqualTo(429));
    }

    @Test
    void bookingWithinLimit_stillProcessesNormally() throws Exception {
        mockMvc.perform(post("/api/public/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Within Limit","contactNo":"0770000003",\
                                "dateOfBirth":"1990-01-01","doctorId":999999,\
                                "appointmentDateTime":"%s","gender":"Male"}
                                """.formatted(LocalDate.now().plusDays(1).atTime(16, 30)))
                        .header("X-Forwarded-For", "10.9.8.120"))
                .andExpect(status().isNotFound()); // unknown doctor, not a 429
    }
}
