package com.onecare.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onecare.backend.entity.AuditLog;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.Role;
import com.onecare.backend.repository.AuditLogRepository;
import com.onecare.backend.repository.PasswordResetTokenRepository;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FR041/FR042, UC26 - read-only, filterable audit trail endpoint:
 * GET /api/audit-logs with actionType/module/userId/date filters,
 * Super Admin + Admin access only, append-only (no write methods).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "auth.rate-limit.enabled=false")
class AuditLogApiIntegrationTest {

    private static final String PASSWORD = "Password@123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User superAdmin;
    private User admin;
    private User doctor;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();

        superAdmin = createUser("superadmin", Role.SUPER_ADMIN);
        admin = createUser("admin", Role.ADMIN);
        doctor = createUser("doctor", Role.DOCTOR);
    }

    private User createUser(String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@onecare.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        user.setIsActive(true);
        user.setFailedAttempts(0);
        return userRepository.save(user);
    }

    private String login(String username) throws Exception {
        String responseBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(responseBody).get("data").get("accessToken").asText();
    }

    private AuditLog seed(User user, String actionType, String entityType, LocalDateTime timestamp) {
        AuditLog row = new AuditLog();
        row.setUser(user);
        row.setAction(actionType);
        row.setActionType(actionType);
        row.setEntityType(entityType);
        row.setTimestamp(timestamp);
        row.setIpAddress("127.0.0.1");
        row.setDescription(actionType + " outcome=SUCCESS target=test.fixture");
        return auditLogRepository.save(row);
    }

    private void seedThreeRows() {
        LocalDateTime now = LocalDateTime.now();
        seed(superAdmin, "PATIENT_CREATE", "PATIENT", now);
        seed(admin, "MEDICINE_CREATE", "MEDICINE", now.minusHours(1));
        seed(doctor, "LOGIN_FAILED", "AUTH", now.minusDays(1));
    }

    private JsonNode search(String token, String queryString) throws Exception {
        String responseBody = mockMvc.perform(get("/api/audit-logs" + queryString)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(responseBody).get("data");
    }

    private static List<String> actionTypes(JsonNode page) {
        List<String> types = new ArrayList<>();
        page.get("content").forEach(node -> types.add(node.get("actionType").asText()));
        return types;
    }

    private static List<LocalDateTime> timestamps(JsonNode page) {
        List<LocalDateTime> values = new ArrayList<>();
        page.get("content").forEach(node ->
                values.add(LocalDateTime.parse(node.get("timestamp").asText())));
        return values;
    }

    @Test
    void superAdminSeesFullTrailNewestFirst() throws Exception {
        String token = login("superadmin");
        seedThreeRows();

        JsonNode page = search(token, "");

        assertThat(page.get("totalElements").asInt()).isGreaterThanOrEqualTo(3);
        List<LocalDateTime> values = timestamps(page);
        for (int i = 1; i < values.size(); i++) {
            assertThat(values.get(i).isAfter(values.get(i - 1))).isFalse();
        }
        assertThat(actionTypes(page)).contains("PATIENT_CREATE", "LOGIN_FAILED");
    }

    @Test
    void adminCanReadAuditTrail() throws Exception {
        String token = login("admin");
        seedThreeRows();

        JsonNode page = search(token, "");

        assertThat(page.get("totalElements").asInt()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void actionTypeFilter_isCaseInsensitiveAndExact() throws Exception {
        String token = login("superadmin");
        seedThreeRows();

        JsonNode page = search(token, "?actionType=login_failed");

        assertThat(actionTypes(page)).containsExactly("LOGIN_FAILED");
    }

    @Test
    void moduleFilter_matchesEntityTypeCaseInsensitively() throws Exception {
        String token = login("superadmin");
        seedThreeRows();

        JsonNode page = search(token, "?module=patient");

        assertThat(actionTypes(page)).containsExactly("PATIENT_CREATE");
    }

    @Test
    void userIdFilter_returnsOnlyThatActorsEntries() throws Exception {
        String token = login("superadmin");
        seedThreeRows();

        JsonNode page = search(token, "?userId=" + doctor.getUserId());

        assertThat(actionTypes(page)).contains("LOGIN_FAILED");
        page.get("content").forEach(node ->
                assertThat(node.get("userId").asLong()).isEqualTo(doctor.getUserId()));
    }

    @Test
    void dateRangeOutsideStoredEntriesReturnsEmptyPageWith200() throws Exception {
        String token = login("superadmin");
        seedThreeRows();

        JsonNode page = search(token, "?dateFrom=" + LocalDate.now().plusDays(1));

        assertThat(page.get("content").isEmpty()).isTrue();
        assertThat(page.get("totalElements").asInt()).isZero();
    }

    @Test
    void pageSizeIsCappedAt100() throws Exception {
        String token = login("superadmin");
        seedThreeRows();

        JsonNode page = search(token, "?size=500");

        assertThat(page.get("size").asInt()).isLessThanOrEqualTo(100);
    }

    @Test
    void doctorCannotReadAuditTrail() throws Exception {
        String token = login("doctor");

        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/audit-logs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void endpointIsAppendOnly_noWriteMethodsExist() throws Exception {
        String token = login("superadmin");
        long rowsBefore = auditLogRepository.count();

        // The controller declares GET only; POST/DELETE are rejected with 405.
        mockMvc.perform(post("/api/audit-logs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(delete("/api/audit-logs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.success").value(false));

        assertThat(auditLogRepository.count()).isEqualTo(rowsBefore);
    }
}
