package com.onecare.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onecare.backend.entity.Patient;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.Gender;
import com.onecare.backend.enums.Role;
import com.onecare.backend.repository.AppointmentRepository;
import com.onecare.backend.repository.AuditLogRepository;
import com.onecare.backend.repository.DoctorScheduleRepository;
import com.onecare.backend.repository.PatientRepository;
import com.onecare.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Backup & restore through the full stack (FR010, UC5): Super Admin only
 * (AC1), AES-256-GCM encrypted files with timestamps (AC1), and the
 * destructive restore guard plus post-completion audit entry (AC4).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "auth.rate-limit.enabled=false")
class BackupRestoreApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private DoctorScheduleRepository doctorScheduleRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String superAdminToken;
    private String adminToken;
    private String doctorToken;
    private User superAdmin;

    @BeforeEach
    void setUp() throws Exception {
        auditLogRepository.deleteAll();
        doctorScheduleRepository.deleteAll();
        appointmentRepository.deleteAll();
        patientRepository.deleteAll();
        userRepository.deleteAll();

        superAdmin = createUser("bkp-superadmin", "bkp-superadmin@onecare.com", Role.SUPER_ADMIN);
        createUser("bkp-admin", "bkp-admin@onecare.com", Role.ADMIN);
        createUser("bkp-doctor", "bkp-doctor@onecare.com", Role.DOCTOR);

        superAdminToken = loginAndGetToken("bkp-superadmin");
        adminToken = loginAndGetToken("bkp-admin");
        doctorToken = loginAndGetToken("bkp-doctor");
    }

    @AfterEach
    void tearDown() {
        auditLogRepository.deleteAll();
        doctorScheduleRepository.deleteAll();
        appointmentRepository.deleteAll();
        patientRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void listAndDownloadBackup_asSuperAdmin_returnTimestampAndEncryptedFile() throws Exception {
        // AC1 happy path
        long backupId = createBackup();

        mockMvc.perform(get("/api/system/backups")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].backupId").value(backupId))
                .andExpect(jsonPath("$.data[0].createdAt").exists())
                .andExpect(jsonPath("$.data[0].filename").isNotEmpty())
                .andExpect(jsonPath("$.data[0].sizeBytes").isNumber());

        var download = mockMvc.perform(get("/api/system/backups/" + backupId + "/download")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment; filename=")))
                .andReturn();

        byte[] content = download.getResponse().getContentAsByteArray();
        assertThat(content.length).isGreaterThan(0);
        assertThat(new String(content, java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("patients", "encrypted-but-readable marker")
                .as("backup file must not be plaintext JSON");
    }

    @Test
    void backupEndpoints_asNonSuperAdmin_return403() throws Exception {
        long backupId = createBackup();

        mockMvc.perform(get("/api/system/backups")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/system/backups/" + backupId + "/download")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/system/backups")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void restore_withoutConfirm_returns400ExplicitConfirmationRequired() throws Exception {
        // AC4 negative
        long backupId = createBackup();

        mockMvc.perform(multipart("/api/system/restore")
                        .param("backupId", String.valueOf(backupId))
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Explicit confirmation required"))
                .andExpect(jsonPath("$.data").doesNotExist());

        assertThat(auditLogRepository.findAll())
                .extracting("action")
                .doesNotContain("SYSTEM_RESTORE");
    }

    @Test
    void restore_withConfirm_restoresDataAndAuditsAfterCompletion() throws Exception {
        // AC4 happy path
        Patient patient = createPatient("Pre-backup Patient");
        long backupId = createBackup();

        patientRepository.deleteAll();
        assertThat(patientRepository.findAll()).isEmpty();

        mockMvc.perform(multipart("/api/system/restore")
                        .param("backupId", String.valueOf(backupId))
                        .param("confirm", "true")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.patients").value(1));

        assertThat(patientRepository.findAll())
                .extracting("fullName")
                .contains("Pre-backup Patient");

        // the audit entry for the restore is written after completion (AC4)
        assertThat(auditLogRepository.findAll())
                .filteredOn(audit -> "SYSTEM_RESTORE".equals(audit.getAction()))
                .isNotEmpty();

        // the acting super admin survived the round trip
        assertThat(userRepository.findById(superAdmin.getUserId())).isPresent();
    }

    @Test
    void restore_asNonSuperAdmin_returns403() throws Exception {
        long backupId = createBackup();

        mockMvc.perform(multipart("/api/system/restore")
                        .param("backupId", String.valueOf(backupId))
                        .param("confirm", "true")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden());
    }

    private long createBackup() throws Exception {
        String responseBody = mockMvc.perform(post("/api/system/backups")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.backupId").isNumber())
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode body = objectMapper.readTree(responseBody);
        return body.get("data").get("backupId").asLong();
    }

    private Patient createPatient(String fullName) {
        Patient patient = new Patient();
        patient.setFullName(fullName);
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        patient.setContactNo("0779999999");
        patient.setGender(Gender.MALE);
        patient.setIsActive(true);
        return patientRepository.save(patient);
    }

    private User createUser(String username, String email, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode("Password@123"));
        user.setRole(role);
        user.setIsActive(true);
        user.setFailedAttempts(0);
        return userRepository.save(user);
    }

    private String loginAndGetToken(String username) throws Exception {
        String responseBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"Password@123"}
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode body = objectMapper.readTree(responseBody);
        return body.get("data").get("accessToken").asText();
    }
}
