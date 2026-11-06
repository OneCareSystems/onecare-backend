package com.onecare.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.Role;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.security.RolePermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC1 (NFRP-006, FR039): GET /api/reports/clinic over a 1-year range on a
 * database seeded to SDS scale (20,000 patients) must respond within 10
 * seconds. The bulk data is inserted with batch JdbcTemplate statements so the
 * measurement covers the report query, not the seeding.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // bulk data rolls back, the shared test database stays clean
class ReportPerformanceIntegrationTest {

    private static final int PATIENT_COUNT = 20_000;
    private static final int APPOINTMENT_COUNT = 20_000;
    private static final int DAYS_IN_RANGE = 365;
    private static final long RESPONSE_THRESHOLD_MS = 10_000L;
    private static final int BATCH_SIZE = 5_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User admin;

    @BeforeEach
    void seedSdsScale() {
        admin = createUser(Role.ADMIN);
        long doctorA = createUser(Role.DOCTOR).getUserId();
        long doctorB = createUser(Role.DOCTOR).getUserId();

        List<Long> patientIds = insertPatients();
        insertAppointments(patientIds, doctorA, doctorB);
    }
    @Test
    void clinicReport_oneYearRangeAtSdsScaleRespondsWithinTenSeconds() throws Exception {
        long start = System.nanoTime();
        MvcResult result = mockMvc.perform(get("/api/reports/clinic")
                        .param("from", "2024-01-01")
                        .param("to", "2024-12-31") // exactly 365 days: the widest allowed range
                        .with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        JsonNode data = JSON.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.get("totalAppointments").asLong()).isEqualTo(APPOINTMENT_COUNT);
        assertThat(data.get("uniquePatients").asLong()).isEqualTo(PATIENT_COUNT);
        assertThat(elapsedMs)
                .as("clinic report over 1 year at SDS scale must respond within %d ms (NFRP-006)",
                        RESPONSE_THRESHOLD_MS)
                .isLessThan(RESPONSE_THRESHOLD_MS);
    }

    // ------------------------------------------------------------------
    // Bulk seeding (JdbcTemplate batches - no entity overhead)
    // ------------------------------------------------------------------

    /**
     * Returns the ids of exactly the rows this method inserted. They are
     * looked up afterwards rather than derived from MIN/MAX so rows other test
     * classes may have committed to the shared database cannot skew the range.
     */
    private List<Long> insertPatients() {
        List<Object[]> batch = new ArrayList<>(PATIENT_COUNT);
        for (int i = 0; i < PATIENT_COUNT; i++) {
            batch.add(new Object[]{
                    "Perf Patient " + i,
                    "1980-01-01",
                    "Address " + i,
                    "+947" + String.format("%08d", i),
                    "perf-" + i + "@onecare.test",
                    "O+",
                    "MALE",
                    true,
                    "2024-01-01 00:00:00",
                    "2024-01-01 00:00:00"
            });
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO patients (full_name, date_of_birth, address, contact_no, email,"
                        + " blood_group, gender, is_active, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                batch);
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT patient_id FROM patients WHERE full_name LIKE 'Perf Patient %'"
                        + " ORDER BY patient_id",
                Long.class);
        assertThat(ids).hasSize(PATIENT_COUNT);
        return ids;
    }

    private void insertAppointments(List<Long> patientIds, long doctorA, long doctorB) {
        List<Object[]> batch = new ArrayList<>(APPOINTMENT_COUNT);
        String[] statuses = {"COMPLETED", "SCHEDULED", "CANCELLED", "COMPLETED"};
        for (int i = 0; i < APPOINTMENT_COUNT; i++) {
            LocalDate date = LocalDate.of(2024, 1, 1).plusDays(i % DAYS_IN_RANGE);
            int slotOffsetMinutes = (i / DAYS_IN_RANGE) * 5; // unique per doctor and day
            int hour = 8 + slotOffsetMinutes / 60;
            int minute = slotOffsetMinutes % 60;
            batch.add(new Object[]{
                    patientIds.get(i),
                    i % 2 == 0 ? doctorA : doctorB,
                    date.toString(),
                    String.format("%02d:%02d:00", hour, minute),
                    statuses[i % statuses.length],
                    "Checkup",
                    "2024-01-01 00:00:00",
                    "2024-01-01 00:00:00"
            });
        }
        String sql = "INSERT INTO appointments (patient_id, doctor_id, appointment_date, time_slot,"
                + " status, reason, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)";
        for (int from = 0; from < batch.size(); from += BATCH_SIZE) {
            jdbcTemplate.batchUpdate(sql, batch.subList(from, Math.min(from + BATCH_SIZE, batch.size())));
        }
    }

    private RequestPostProcessor as(User user) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        RolePermission.getPermissions(user.getRole()).stream()
                .map(SimpleGrantedAuthority::new)
                .forEach(authorities::add);
        return user(user.getUsername()).authorities(authorities);
    }

    private User createUser(Role role) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("perf-" + role.name().toLowerCase() + "-" + unique);
        user.setEmail("perf-" + unique + "@onecare.test");
        user.setPasswordHash("hash");
        user.setRole(role);
        user.setIsActive(true);
        user.setFailedAttempts(0);
        return userRepository.save(user);
    }
}
