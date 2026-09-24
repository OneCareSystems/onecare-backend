package com.onecare.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.rate-limit.enabled=true",
        "auth.rate-limit.requests-per-minute=2",
        "auth.rate-limit.window-seconds=60"
})
@AutoConfigureMockMvc
class RateLimitFilterIntegrationTest {

    private static final String CLIENT_IP = "10.9.8.7";

    @Autowired
    private MockMvc mockMvc;

    private void forgotPassword(String ip) throws Exception {
        forgotPassword(ip, 200);
    }

    private void forgotPassword(String ip, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"unknown-for-rate-limit@onecare.com\"}")
                        .header("X-Forwarded-For", ip))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void shouldReturn429OnThirdRequestWhenLimitIsTwo() throws Exception {
        forgotPassword(CLIENT_IP);
        forgotPassword(CLIENT_IP);

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"unknown-for-rate-limit@onecare.com\"}")
                        .header("X-Forwarded-For", CLIENT_IP))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.emptyString())))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Too many requests. Please try again later."));
    }

    @Test
    void shouldTrackClientsIndependently() throws Exception {
        // Exhaust the limit for one client...
        forgotPassword("10.9.8.21");
        forgotPassword("10.9.8.21");
        forgotPassword("10.9.8.21", 429);

        // ...an unrelated client is unaffected
        forgotPassword("10.9.8.22");
    }

    @Test
    void shouldNotRateLimitNonAuthPaths() throws Exception {
        forgotPassword("10.9.8.31");
        forgotPassword("10.9.8.31");
        forgotPassword("10.9.8.31", 429);

        // Over the auth limit for this IP, but non-auth paths are untouched:
        // unauthenticated access is rejected by security (401/403), not by the limiter (429)
        mockMvc.perform(post("/api/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("X-Forwarded-For", "10.9.8.31"))
                .andExpect(result -> org.assertj.core.api.Assertions
                        .assertThat(result.getResponse().getStatus()).isNotEqualTo(429));
    }
}
