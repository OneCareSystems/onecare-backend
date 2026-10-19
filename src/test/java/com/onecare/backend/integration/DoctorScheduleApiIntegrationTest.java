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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Doctor schedule configuration through the full stack (FR009, UC4):
 * controller -> service -> repository on the test DB, plus the DDP-20
 * re-verification (AC3): available slots must come from the real schedule.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "auth.rate-limit.enabled=false")
class DoctorScheduleApiIntegrationTest {

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
    private String doctorToken;
    private String adminToken;
    private User doctor;

    @BeforeEach
    void setUp() throws Exception {
        auditLogRepository.deleteAll();
        doctorScheduleRepository.deleteAll();
        appointmentRepository.deleteAll();
        patientRepository.deleteAll();
        userRepository.deleteAll();

        createUser("sched-superadmin", "sched-superadmin@onecare.com", Role.SUPER_ADMIN);
        doctor = createUser("sched-doctor", "sched-doctor@onecare.com", Role.DOCTOR);
        createUser("sched-admin", "sched-admin@onecare.com", Role.ADMIN);

        superAdminToken = loginAndGetToken("sched-superadmin");
        doctorToken = loginAndGetToken("sched-doctor");
        adminToken = loginAndGetToken("sched-admin");
    }

    @AfterEach
    void tearDown() {
        auditLogRepository.deleteAll();
        doctorScheduleRepository.deleteAll();
    }

    @Test
    void saveSchedule_overlappingSlots_returns400TimeSlotConflict() throws Exception {
        // AC2: UC4 alt flow — overlapping slots are rejected, conflicts returned
        mockMvc.perform(put("/api/doctors/" + doctor.getUserId() + "/schedule")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workingDays": ["MONDAY"],
                                  "timeSlots": [
                                    {"start": "09:00", "end": "12:00"},
                                    {"start": "11:00", "end": "13:00"}
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Time slot conflict"))
                .andExpect(jsonPath("$.data.conflictingSlots").isArray())
                .andExpect(jsonPath("$.data.conflictingSlots.length()").value(2));

        assertThat(doctorScheduleRepository.findAll()).isEmpty();
    }

    @Test
    void getSchedule_asSuperAdmin_returnsConfiguredWorkingDaysAndSlots() throws Exception {
        saveValidSchedule();

        mockMvc.perform(get("/api/doctors/" + doctor.getUserId() + "/schedule")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.doctorId").value(doctor.getUserId()))
                .andExpect(jsonPath("$.data.workingDays[0]").value("MONDAY"))
                .andExpect(jsonPath("$.data.timeSlots[0].start").value("09:00"))
                .andExpect(jsonPath("$.data.timeSlots[0].end").value("12:00"));
    }

    @Test
    void scheduleEndpoints_asDoctorOrAdminWithoutAccess_return403() throws Exception {
        // write: only Super Admin (SCHEDULE_MANAGE)
        mockMvc.perform(put("/api/doctors/" + doctor.getUserId() + "/schedule")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validScheduleJson()))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/doctors/" + doctor.getUserId() + "/schedule")
                        .header("Authorization", "Bearer " + doctorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validScheduleJson()))
                .andExpect(status().isForbidden());
    }

    @Test
    void doctorReadingAnotherDoctorsSchedule_returns403() throws Exception {
        User otherDoctor = createUser("sched-other-doctor", "sched-other@onecare.com", Role.DOCTOR);

        mockMvc.perform(get("/api/doctors/" + otherDoctor.getUserId() + "/schedule")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void availableSlots_useRealDoctorSchedule_notClinicHours() throws Exception {
        // AC3: DDP-20's slot check must return real slots from the schedule.
        // Test clinic hours are 16:00-21:00, so a 09:00-12:00 schedule proves
        // the schedule (not the stub) drives availability.
        saveValidSchedule();

        LocalDate nextMonday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));

        mockMvc.perform(get("/api/appointments/available-slots")
                        .param("doctorId", String.valueOf(doctor.getUserId()))
                        .param("date", nextMonday.toString())
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("09:00"))
                .andExpect(jsonPath("$.data").isNotEmpty());
    }

    @Test
    void availableSlots_onNonWorkingDay_areEmpty() throws Exception {
        saveValidSchedule();

        LocalDate nextSaturday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.SATURDAY));

        mockMvc.perform(get("/api/appointments/available-slots")
                        .param("doctorId", String.valueOf(doctor.getUserId()))
                        .param("date", nextSaturday.toString())
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void bookingOutsideWorkingHours_isRejectedWithConflict() throws Exception {
        saveValidSchedule();

        Patient patient = new Patient();
        patient.setFullName("Schedule Test Patient");
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        patient.setContactNo("0771234567");
        patient.setGender(Gender.MALE);
        patient.setIsActive(true);
        patientRepository.save(patient);

        // next Monday 16:00 is inside the clinic-hours stub but NOT inside the
        // doctor's configured 09:00-12:00 schedule -> must be rejected
        LocalDate nextMonday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        LocalDateTime outsideSchedule = nextMonday.atTime(16, 0);

        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"doctorId": %d, "patientId": %d, "appointmentDateTime": "%s", "reason": "Checkup"}
                                """.formatted(doctor.getUserId(), patient.getPatientId(), outsideSchedule)))
                .andExpect(status().isConflict());

        // booking inside the schedule succeeds
        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"doctorId": %d, "patientId": %d, "appointmentDateTime": "%s", "reason": "Checkup"}
                                """.formatted(doctor.getUserId(), patient.getPatientId(), nextMonday.atTime(9, 0))))
                .andExpect(status().isCreated());
    }

    private void saveValidSchedule() throws Exception {
        mockMvc.perform(put("/api/doctors/" + doctor.getUserId() + "/schedule")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validScheduleJson()))
                .andExpect(status().isOk());
    }

    private String validScheduleJson() {
        return """
                {
                  "workingDays": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"],
                  "timeSlots": [{"start": "09:00", "end": "12:00"}]
                }
                """;
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
