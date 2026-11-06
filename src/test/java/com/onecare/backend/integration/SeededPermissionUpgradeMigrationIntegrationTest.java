package com.onecare.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.Role;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.security.AppUserDetailsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Upgrade path for databases seeded before MEDICINE_READ existed (review
 * comment on the static RolePermission defaults).
 *
 * <p>Adding MEDICINE_READ to {@code RolePermission.getPermissions(DOCTOR)} only
 * affects fresh databases: {@code RolePermissionSeeder} skips any role that
 * already has rows in {@code role_permissions}, and {@code AppUserDetailsService}
 * reads those persisted rows (falling back to the static defaults only while a
 * role has none). Staging/prod also run with {@code spring.sql.init.mode=never},
 * so the grant must arrive as a deployment migration —
 * {@code migrations/001_grant_doctor_medicine_read.sql}.
 *
 * <p>This test reproduces the pre-upgrade state (DOCTOR rows persisted, MEDICINE_READ
 * missing), proves authentication honours those persisted rows over the static
 * defaults (403), executes the committed migration file, and proves the very
 * same JWT then succeeds (200). Authorities are reloaded from the database on
 * every request by {@code JwtAuthFilter}, so no re-login is needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SeededPermissionUpgradeMigrationIntegrationTest {

    private static final Path MIGRATION =
            Path.of("migrations", "001_grant_doctor_medicine_read.sql");
    private static final String MEDICINE_READ = "MEDICINE_READ";
    private static final String PASSWORD = "Password@123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AppUserDetailsService userDetailsService;

    private String doctorUsername;
    private String token;

    @BeforeEach
    void simulatePreUpgradeDatabase() throws Exception {
        // A doctor created BEFORE MEDICINE_READ existed: the DOCTOR role has
        // persisted permission rows, just without MEDICINE_READ.
        doctorUsername = "upgrade-doctor-" + System.nanoTime();
        User doctor = new User();
        doctor.setUsername(doctorUsername);
        doctor.setEmail(doctorUsername + "@onecare.com");
        doctor.setPasswordHash(passwordEncoder.encode(PASSWORD));
        doctor.setRole(Role.DOCTOR);
        doctor.setIsActive(true);
        doctor.setFailedAttempts(0);
        doctor = userRepository.save(doctor);

        jdbcTemplate.update(
                "DELETE FROM role_permissions WHERE role = ? AND permission = ?",
                Role.DOCTOR.name(), MEDICINE_READ);

        token = loginAndGetToken(doctorUsername);
    }

    @AfterEach
    void restoreGrant() throws Exception {
        // Never leave the shared application context without the grant.
        runMigration();
    }

    @Test
    void previouslySeededDoctor_isDeniedUntilTheMigrationRuns() throws Exception {
        // Authenticated against the PRE-UPGRADE persisted permissions: the
        // static default does not apply while rows exist -> 403.
        UserDetails before = userDetailsService.loadUserByUsername(doctorUsername);
        assertThat(authorities(before)).contains("ROLE_DOCTOR").doesNotContain(MEDICINE_READ);

        mockMvc.perform(get("/api/medicines")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        // Deployment migration applied...
        runMigration();

        // ...same JWT, authorities reloaded from the migrated rows -> 200.
        UserDetails after = userDetailsService.loadUserByUsername(doctorUsername);
        assertThat(authorities(after)).contains("ROLE_DOCTOR", MEDICINE_READ);

        mockMvc.perform(get("/api/medicines")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/medicines/available")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void migration_isIdempotentAndKeepsASingleRow() throws Exception {
        runMigration();
        runMigration();

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM role_permissions WHERE role = ? AND permission = ?",
                Integer.class, Role.DOCTOR.name(), MEDICINE_READ);
        assertThat(rows).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void runMigration() throws Exception {
        jdbcTemplate.execute(Files.readString(MIGRATION));
    }

    private List<String> authorities(UserDetails details) {
        return details.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }

    private String loginAndGetToken(String username) throws Exception {
        String responseBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode data = objectMapper.readTree(responseBody).get("data");
        assertThat(data).isNotNull();
        return data.get("accessToken").asText();
    }
}
