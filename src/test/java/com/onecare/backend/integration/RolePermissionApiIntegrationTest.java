package com.onecare.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.Role;
import com.onecare.backend.repository.AuditLogRepository;
import com.onecare.backend.repository.RolePermissionEntityRepository;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.security.RolePermission;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Configurable role permissions through the full stack (FR003, UC3), with the
 * BR004-BR006 hard rules enforced at the endpoint (AC5) and the read API (AC6).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "auth.rate-limit.enabled=false")
class RolePermissionApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RolePermissionEntityRepository rolePermissionEntityRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String superAdminToken;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        auditLogRepository.deleteAll();
        userRepository.deleteAll();
        seedDefaultPermissions();

        createUser("perm-superadmin", "perm-superadmin@onecare.com", Role.SUPER_ADMIN);
        createUser("perm-admin", "perm-admin@onecare.com", Role.ADMIN);

        superAdminToken = loginAndGetToken("perm-superadmin");
        adminToken = loginAndGetToken("perm-admin");
    }

    @AfterEach
    void tearDown() {
        // leave no customized role sets behind for other test classes:
        // deleting the rows makes authentication fall back to the defaults
        auditLogRepository.deleteAll();
        rolePermissionEntityRepository.deleteAll();
        userRepository.deleteAll();
    }

    /** Restores the seeded defaults so every test starts from a known state. */
    private void seedDefaultPermissions() {
        rolePermissionEntityRepository.deleteAll();
        for (Role role : Role.values()) {
            RolePermission.getPermissions(role).forEach(permission ->
                    rolePermissionEntityRepository.save(
                            new com.onecare.backend.entity.RolePermissionEntity(role, permission)));
        }
    }

    @Test
    void getPermissions_returnsCurrentSet() throws Exception {
        // AC6: UC3 step 2
        mockMvc.perform(get("/api/roles/ADMIN/permissions")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.permissions").isArray())
                .andExpect(jsonPath("$.data.permissions").isNotEmpty());
    }

    @Test
    void changeSuperAdminOwnPermissions_returns422Br005_andPersistsNothing() throws Exception {
        // AC5 negative: BR005 — the Super Admin's own access cannot be removed
        mockMvc.perform(put("/api/roles/SUPER_ADMIN/permissions")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permissions": ["PATIENT_READ"]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("BR005")));

        assertThat(rolePermissionEntityRepository.findByRole(Role.SUPER_ADMIN))
                .extracting("permission")
                .contains("SYSTEM_RESTORE");
    }

    @Test
    void grantPrivilegedPermissionToAdmin_returns422Br004_andNothingPersisted() throws Exception {
        // AC5 negative: BR004 — SYSTEM_BACKUP is SUPER_ADMIN-only
        mockMvc.perform(put("/api/roles/ADMIN/permissions")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permissions": ["PATIENT_READ", "SYSTEM_BACKUP"]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("BR004")));

        assertThat(rolePermissionEntityRepository.findByRole(Role.ADMIN))
                .extracting("permission")
                .doesNotContain("SYSTEM_BACKUP");
    }

    @Test
    void emptyPermissionSet_returns422Br006() throws Exception {
        // AC5 negative: BR006 — a role cannot end up with zero permissions
        mockMvc.perform(put("/api/roles/ADMIN/permissions")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permissions": []}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("BR006")));
    }

    @Test
    void unknownPermission_returns400_andPersistsNothing() throws Exception {
        rolePermissionEntityRepository.deleteAll(rolePermissionEntityRepository.findByRole(Role.ADMIN));

        mockMvc.perform(put("/api/roles/ADMIN/permissions")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permissions": ["PATIENT_READ", "NOT_A_PERMISSION"]}
                                """))
                .andExpect(status().isBadRequest());

        assertThat(rolePermissionEntityRepository.findByRole(Role.ADMIN)).isEmpty();
    }

    @Test
    void validChange_isPersistedAndVisibleToAuthentication() throws Exception {
        mockMvc.perform(put("/api/roles/ADMIN/permissions")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permissions": ["PATIENT_READ_ALL", "PATIENT_READ"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions").isArray());

        mockMvc.perform(get("/api/roles/ADMIN/permissions")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions.length()").value(2));

        // the changed set is live immediately (authorities reload per request):
        // this admin token no longer holds INVOICE_READ, so invoices is denied
        mockMvc.perform(get("/api/invoices")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden());

        // but the kept permission still works
        mockMvc.perform(get("/api/patients")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void permissionEndpoints_asNonSuperAdmin_return403() throws Exception {
        mockMvc.perform(get("/api/roles/ADMIN/permissions")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/roles/ADMIN/permissions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permissions": ["PATIENT_READ"]}
                                """))
                .andExpect(status().isForbidden());
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
