package com.onecare.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RbacAccessControlTest {

    private static final String TEST_PASSWORD = "Password@123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    private String superAdminToken;
    private String adminToken;
    private String doctorToken;
    private String pharmacistToken;

    @BeforeEach
    void setupUsers() throws Exception {
        userRepository.deleteAll();

        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);

        createUser(
                "superadmin",
                "superadmin@onecare.com",
                Role.SUPER_ADMIN,
                passwordHash
        );

        createUser(
                "admin",
                "admin@onecare.com",
                Role.ADMIN,
                passwordHash
        );

        createUser(
                "doctor",
                "doctor@onecare.com",
                Role.DOCTOR,
                passwordHash
        );

        createUser(
                "pharmacist",
                "pharmacist@onecare.com",
                Role.PHARMACIST,
                passwordHash
        );

        superAdminToken = loginAndGetToken("superadmin");
        adminToken = loginAndGetToken("admin");
        doctorToken = loginAndGetToken("doctor");
        pharmacistToken = loginAndGetToken("pharmacist");
    }

    private User createUser(
            String username,
            String email,
            Role role,
            String passwordHash
    ) {
        User user = new User();

        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordHash);
        user.setRole(role);
        user.setIsActive(true);
        user.setFailedAttempts(0);

        return userRepository.save(user);
    }

    private String loginAndGetToken(String username) throws Exception {
        String responseBody = mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                            "username": "%s",
                                            "password": "%s"
                                        }
                                        """.formatted(username, TEST_PASSWORD))
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode body = objectMapper.readTree(responseBody);

        assertThat(body.get("accessToken")).isNotNull();

        return body.get("accessToken").asText();
    }

    private void assertAllowed(String endpoint, String token)
            throws Exception {

        mockMvc.perform(
                        get(endpoint)
                                .header(
                                        "Authorization",
                                        "Bearer " + token
                                )
                )
                .andExpect(status().isOk());
    }

    private void assertForbidden(String endpoint, String token)
            throws Exception {

        mockMvc.perform(
                        get(endpoint)
                                .header(
                                        "Authorization",
                                        "Bearer " + token
                                )
                )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(
                        jsonPath("$.message")
                                .value("Access denied: insufficient permissions")
                );
    }

    @Test
    void superAdmin_canAccessAllProtectedEndpoints() throws Exception {

        assertAllowed("/api/users", superAdminToken);
        assertAllowed("/api/patients", superAdminToken);
        assertAllowed("/api/medicines", superAdminToken);
        assertAllowed("/api/prescriptions", superAdminToken);
//        assertAllowed("/api/appointments", superAdminToken);
//        assertAllowed("/api/audit", superAdminToken);
    }

    @Test
    void admin_canAccessAdminPermittedEndpoints() throws Exception {

        assertAllowed("/api/users", adminToken);
        assertAllowed("/api/patients", adminToken);
        assertAllowed("/api/medicines", adminToken);
//        assertAllowed("/api/prescriptions", adminToken);
//        assertAllowed("/api/appointments", adminToken);
//        assertAllowed("/api/audit", adminToken);
    }

    @Test
    void doctor_hasOnlyDoctorPermittedAccess() throws Exception {

        assertForbidden("/api/users", doctorToken);

        assertAllowed("/api/patients", doctorToken);

        assertForbidden("/api/medicines", doctorToken);

        assertAllowed("/api/prescriptions", doctorToken);

//        assertAllowed("/api/appointments", doctorToken);

        assertForbidden("/api/audit", doctorToken);
    }

    @Test
    void pharmacist_hasOnlyPharmacistPermittedAccess() throws Exception {

        assertForbidden("/api/users", pharmacistToken);

        assertForbidden("/api/patients", pharmacistToken);

        assertAllowed("/api/medicines", pharmacistToken);

        assertAllowed("/api/prescriptions", pharmacistToken);

        assertForbidden("/api/appointments", pharmacistToken);

        assertForbidden("/api/audit", pharmacistToken);
    }

    @Test
    void unauthenticatedUser_cannotAccessProtectedEndpoints()
            throws Exception {

        String[] protectedEndpoints = {
                "/api/users",
                "/api/patients",
                "/api/medicines",
                "/api/prescriptions",
                "/api/appointments",
                "/api/audit"
        };

        for (String endpoint : protectedEndpoints) {
            mockMvc.perform(get(endpoint))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void login_returnsAuthenticatedUserRole() throws Exception {

        String responseBody = mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                            "username": "doctor",
                                            "password": "%s"
                                        }
                                        """.formatted(TEST_PASSWORD))
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode body = objectMapper.readTree(responseBody);

        assertThat(body.get("accessToken")).isNotNull();
        assertThat(body.get("refreshToken")).isNotNull();
        assertThat(body.get("role").asText()).isEqualTo("DOCTOR");
    }

    @Test
    void me_returnsAuthenticatedUserWithoutPassword() throws Exception {

        String responseBody = mockMvc.perform(
                        get("/api/auth/me")
                                .header(
                                        "Authorization",
                                        "Bearer " + doctorToken
                                )
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode body = objectMapper.readTree(responseBody);
        JsonNode data = body.get("data");

        assertThat(data.get("username").asText())
                .isEqualTo("doctor");

        assertThat(data.get("role").asText())
                .isEqualTo("DOCTOR");

        assertThat(data.get("email").asText())
                .isEqualTo("doctor@onecare.com");

        assertThat(data.has("passwordHash"))
                .isFalse();
    }
}
