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
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.oneOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC6 (FR043): the public endpoints — public booking, available slots and the
 * chatbot query — are reachable WITHOUT a JWT and are rate limited instead of
 * being rejected with 401/403. Every other endpoint returns 401 without a
 * token (the "protected" assertions below; the full matrix's no-token cells
 * live in {@link Table9PermissionMatrixIntegrationTest}).
 */
@SpringBootTest(properties = {
        "auth.rate-limit.enabled=false",
        "public-booking.rate-limit.enabled=true",
        "public-booking.rate-limit.requests-per-minute=2",
        "public-booking.rate-limit.window-seconds=60",
        "api.rate-limit.trusted-proxies=127.0.0.1"
})
@AutoConfigureMockMvc
class PublicEndpointAccessIntegrationTest {

    private static final String SLOTS_IP = "10.9.9.101";
    private static final String BOOKING_IP = "10.9.9.102";
    private static final String CHATBOT_IP = "10.9.9.103";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    private Long doctorId;

    @BeforeEach
    void setupDoctor() {
        // The context is fresh for this property set; the doctor only has to
        // exist so the available-slots lookup resolves.
        userRepository.deleteAll();
        User doctor = new User();
        doctor.setUsername("public-doctor");
        doctor.setEmail("public-doctor@onecare.com");
        doctor.setPasswordHash("hash");
        doctor.setRole(Role.DOCTOR);
        doctor.setIsActive(true);
        doctor.setFailedAttempts(0);
        doctorId = userRepository.save(doctor).getUserId();
    }

    @Test
    void availableSlots_reachableWithoutJwt_andRateLimited() throws Exception {
        String url = "/api/appointments/available-slots?doctorId=" + doctorId
                + "&date=" + LocalDate.now().plusDays(1);

        // Not 401/403 — the slot lookup is part of the public booking flow (AC6)
        mockMvc.perform(get(url).header("X-Forwarded-For", SLOTS_IP))
                .andExpect(status().isOk());

        // ...and it is rate limited (429), the public-booking tier (limit: 2)
        mockMvc.perform(get(url).header("X-Forwarded-For", SLOTS_IP))
                .andExpect(status().isOk());
        mockMvc.perform(get(url).header("X-Forwarded-For", SLOTS_IP))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", not(emptyString())))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void publicBooking_reachableWithoutJwt() throws Exception {
        mockMvc.perform(post("/api/public/appointments")
                        .header("X-Forwarded-For", BOOKING_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is(not(oneOf(401, 403))));
    }

    @Test
    void chatbotQuery_reachableWithoutJwt() throws Exception {
        // The chatbot proxies to a backend that may be down in this test
        // environment — the AC is only that the call is never rejected by
        // authentication/authorization.
        mockMvc.perform(post("/api/chatbot/query")
                        .header("X-Forwarded-For", CHATBOT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"hello\"}"))
                .andExpect(status().is(not(oneOf(401, 403))));
    }

    @Test
    void protectedEndpoints_return401WithoutJwt() throws Exception {
        mockMvc.perform(get("/api/prescriptions")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/appointments")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/patients")).andExpect(status().isUnauthorized());
    }
}
