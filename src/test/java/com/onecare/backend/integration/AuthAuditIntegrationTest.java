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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FR041/FR042 - authentication flows must leave audit trail entries.
 * PR 16/23/26 previously logged only to SLF4J; these tests pin the retrofit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "auth.rate-limit.enabled=false")
class AuthAuditIntegrationTest {

    private static final String PASSWORD = "Password@123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

      @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        // audit rows are committed in their own transaction and must be
        // removed before users (audit_log.user_id FK) can be cleared
        auditLogRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();

        User user = new User();
        user.setUsername("doctor1");
        user.setEmail("doctor1@onecare.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.DOCTOR);
        user.setIsActive(true);
        user.setFailedAttempts(0);
        userRepository.save(user);
    }

    private List<AuditLog> rows(String actionType) {
        return auditLogRepository.findAll().stream()
                .filter(row -> actionType.equals(row.getActionType()))
                .toList();
    }

    private JsonNode login(String username, String password) throws Exception {
        String responseBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(responseBody);
    }

    @Test
    void successfulLogin_isAuditedWithActorAndModuleAuth() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"doctor1","password":"%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isOk());

        List<AuditLog> logins = rows("LOGIN");
        assertThat(logins).hasSize(1);

        AuditLog row = logins.get(0);
        assertThat(row.getUser()).isNotNull();
        assertThat(row.getUser().getUsername()).isEqualTo("doctor1");
        assertThat(row.getEntityType()).isEqualTo("AUTH");
        assertThat(row.getDescription())
                .contains("outcome=SUCCESS")
                .doesNotContain(PASSWORD);
        assertThat(row.getIpAddress()).isNotBlank();
        assertThat(row.getTimestamp()).isNotNull();
    }

    @Test
    void failedLoginForKnownUser_isAuditedAsLoginFailedWithActor() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"doctor1","password":"WrongPassword@99"}
                                """))
                .andExpect(status().isUnauthorized());

        List<AuditLog> failures = rows("LOGIN_FAILED");
        assertThat(failures).hasSize(1);

        AuditLog row = failures.get(0);
        assertThat(row.getUser()).isNotNull();
        assertThat(row.getUser().getUsername()).isEqualTo("doctor1");
        assertThat(row.getEntityType()).isEqualTo("AUTH");
        assertThat(row.getDescription()).contains("outcome=FAILURE");
        assertThat(row.getDescription()).doesNotContain("WrongPassword@99");
    }

    @Test
    void failedLoginForUnknownUser_isAuditedWithoutActor() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ghost","password":"Whatever@99"}
                                """))
                .andExpect(status().isUnauthorized());

        List<AuditLog> failures = rows("LOGIN_FAILED");
        assertThat(failures).hasSize(1);

        AuditLog row = failures.get(0);
        assertThat(row.getUser()).isNull();
        assertThat(row.getEntityType()).isEqualTo("AUTH");
        assertThat(row.getDescription())
                .contains("outcome=FAILURE")
                .doesNotContain("Whatever@99");
    }

    @Test
    void tokenRefresh_isAuditedWithActorAndModuleAuth() throws Exception {
        JsonNode loginBody = login("doctor1", PASSWORD);
        assertThat(loginBody.get("success").asBoolean()).isTrue();

        String refreshToken = loginBody.get("data").get("refreshToken").asText();

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isOk());

        List<AuditLog> refreshes = rows("TOKEN_REFRESH");
        assertThat(refreshes).hasSize(1);

        AuditLog row = refreshes.get(0);
        assertThat(row.getUser()).isNotNull();
        assertThat(row.getUser().getUsername()).isEqualTo("doctor1");
        assertThat(row.getEntityType()).isEqualTo("AUTH");
        assertThat(row.getDescription()).contains("outcome=SUCCESS");
    }

    @Test
    void noAuditDescriptionEverContainsRawPasswords() throws Exception {
        login("doctor1", PASSWORD);
        login("doctor1", "WrongPassword@99");
        login("ghost", "Whatever@99");

        assertThat(auditLogRepository.findAll())
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.getDescription())
                        .doesNotContain(PASSWORD)
                        .doesNotContain("WrongPassword@99")
                        .doesNotContain("Whatever@99"));
    }
}
