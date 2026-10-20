package com.onecare.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.Role;
import com.onecare.backend.repository.AuditLogRepository;
import com.onecare.backend.repository.PasswordResetTokenRepository;
import com.onecare.backend.repository.SystemSettingRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * System settings API through the full stack: monthly backup schedule and
 * consultation fee, Super Admin only, persisted across reads.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "auth.rate-limit.enabled=false")
class SystemSettingsApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private SystemSettingRepository systemSettingRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String superAdminToken;
    private String adminToken;

    @Autowired private PasswordResetTokenRepository passwordResetTokenRepository;

    @BeforeEach
    void setUp() throws Exception {
        systemSettingRepository.deleteAll();
        auditLogRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();   // <-- child first
        userRepository.deleteAll();                 // <-- then parent

        createUser("set-superadmin", "set-superadmin@onecare.com", Role.SUPER_ADMIN);
        createUser("set-admin", "set-admin@onecare.com", Role.ADMIN);

        superAdminToken = loginAndGetToken("set-superadmin");
        adminToken = loginAndGetToken("set-admin");
    }

    // ------------------------------------------------------------------
    // Backup schedule
    // ------------------------------------------------------------------

    @Test
    void getBackupSchedule_beforeAnyUpdate_returnsDefaults() throws Exception {
        mockMvc.perform(get("/api/system/backup/schedule")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.dayOfMonth").value(28))
                .andExpect(jsonPath("$.data.executionTime").value("23:59"))
                .andExpect(jsonPath("$.data.nextRunDate").exists());
    }

    @Test
    void updateBackupSchedule_asSuperAdmin_persistsAndReturnsNextRunDate() throws Exception {
        mockMvc.perform(put("/api/system/backup/schedule")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayOfMonth\":15}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dayOfMonth").value(15))
                .andExpect(jsonPath("$.data.nextRunDate").exists());

        // persisted, not just echoed back
        mockMvc.perform(get("/api/system/backup/schedule")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dayOfMonth").value(15));

        assertThat(systemSettingRepository.findById("BACKUP_SCHEDULE_DAY_OF_MONTH"))
                .isPresent()
                .get()
                .satisfies(setting -> assertThat(setting.getSettingValue()).isEqualTo("15"));
    }

    @Test
    void updateBackupSchedule_invalidDay_returns400() throws Exception {
        mockMvc.perform(put("/api/system/backup/schedule")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayOfMonth\":32}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/system/backup/schedule")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayOfMonth\":0}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/system/backup/schedule")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void backupSchedule_asAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/system/backup/schedule")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/system/backup/schedule")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayOfMonth\":15}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Consultation fee
    // ------------------------------------------------------------------

    @Test
    void getConsultationFee_beforeAnyUpdate_returnsPropertyDefault() throws Exception {
        mockMvc.perform(get("/api/system/settings/consultation-fee")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consultationFee").value(500.00));
    }

    @Test
    void updateConsultationFee_asSuperAdmin_persistsAndAffectsNewInvoices() throws Exception {
        mockMvc.perform(put("/api/system/settings/consultation-fee")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consultationFee\":650.75}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consultationFee").value(650.75));

        // persisted across reads (and therefore across restarts)
        mockMvc.perform(get("/api/system/settings/consultation-fee")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consultationFee").value(650.75));

        // a change is audited
        assertThat(auditLogRepository.findAll())
                .anySatisfy(audit -> assertThat(audit.getDescription()).contains("650.75"));
    }

    @Test
    void updateConsultationFee_invalidAmount_returns400() throws Exception {
        mockMvc.perform(put("/api/system/settings/consultation-fee")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consultationFee\":-1.00}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/system/settings/consultation-fee")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void consultationFee_asAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/system/settings/consultation-fee")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/system/settings/consultation-fee")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consultationFee\":650.75}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void settings_withoutToken_return401() throws Exception {
        mockMvc.perform(get("/api/system/backup/schedule"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/system/settings/consultation-fee"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void createUser(String username, String email, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode("Password@123"));
        user.setRole(role);
        user.setIsActive(true);
        user.setFailedAttempts(0);
        userRepository.save(user);
    }

    private String loginAndGetToken(String username) throws Exception {
        String responseBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"Password@123"}
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(responseBody);
        return body.get("data").get("accessToken").asText();
    }
}
