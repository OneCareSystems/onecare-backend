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
import com.onecare.backend.repository.ExternalDispensingRepository;
import com.onecare.backend.repository.PatientRepository;
import com.onecare.backend.repository.PrescriptionItemRepository;
import com.onecare.backend.repository.PrescriptionRepository;
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

    @Autowired 
    private PrescriptionRepository prescriptionRepository;
    
    @Autowired 
    private PrescriptionItemRepository prescriptionItemRepository;
    
    @Autowired 
    private ExternalDispensingRepository externalDispensingRepository;


    private String superAdminToken;
    private String doctorToken;
    private String adminToken;
    private User doctor;

    @BeforeEach
    void setUp() throws Exception {
        auditLogRepository.deleteAll();

        externalDispensingRepository.deleteAll();
        prescriptionItemRepository.deleteAll();
        prescriptionRepository.deleteAll();
        
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
                                  "days": [
                                    {"dayOfWeek": "MONDAY", "timeSlots": [
                                      {"start": "09:00", "end": "12:00"},
                                      {"start": "11:00", "end": "13:00"}
                                    ]}
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
                .andExpect(jsonPath("$.data.days.length()").value(7))
                .andExpect(jsonPath("$.data.days[0].dayOfWeek").value("MONDAY"))
                .andExpect(jsonPath("$.data.days[0].timeSlots[0].start").value("09:00"))
                .andExpect(jsonPath("$.data.days[0].timeSlots[0].end").value("12:00"));
    }

    @Test
    void getSchedule_activeDoctorWithoutSchedule_returnsDefaultDailyWindow() throws Exception {
        // no schedule saved: an active doctor automatically works 4 PM - 9 PM
        // on every day of the week instead of showing as empty
        mockMvc.perform(get("/api/doctors/" + doctor.getUserId() + "/schedule")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days.length()").value(7))
                .andExpect(jsonPath("$.data.days[0].dayOfWeek").value("MONDAY"))
                .andExpect(jsonPath("$.data.days[0].timeSlots[0].start").value("16:00"))
                .andExpect(jsonPath("$.data.days[0].timeSlots[0].end").value("21:00"))
                .andExpect(jsonPath("$.data.days[6].dayOfWeek").value("SUNDAY"))
                .andExpect(jsonPath("$.data.days[6].timeSlots[0].start").value("16:00"))
                .andExpect(jsonPath("$.data.days[6].timeSlots[0].end").value("21:00"));
    }

    @Test
    void allDaysOff_remainsConfiguredAndDoesNotFallBackToClinicHours() throws Exception {
        mockMvc.perform(put("/api/doctors/" + doctor.getUserId() + "/schedule")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "days": [
                                    {"dayOfWeek": "MONDAY", "timeSlots": []},
                                    {"dayOfWeek": "TUESDAY", "timeSlots": []},
                                    {"dayOfWeek": "WEDNESDAY", "timeSlots": []},
                                    {"dayOfWeek": "THURSDAY", "timeSlots": []},
                                    {"dayOfWeek": "FRIDAY", "timeSlots": []},
                                    {"dayOfWeek": "SATURDAY", "timeSlots": []},
                                    {"dayOfWeek": "SUNDAY", "timeSlots": []}
                                  ]
                                }
                                """))
                .andExpect(status().isOk());

        assertThat(doctorScheduleRepository.findAll())
                .hasSize(7)
                .allMatch(schedule -> !schedule.isAvailable());

        mockMvc.perform(get("/api/doctors/" + doctor.getUserId() + "/schedule")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days.length()").value(7))
                .andExpect(jsonPath("$.data.days[0].timeSlots").isEmpty())
                .andExpect(jsonPath("$.data.days[6].timeSlots").isEmpty());

        LocalDate nextMonday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        mockMvc.perform(get("/api/appointments/available-slots")
                        .param("doctorId", String.valueOf(doctor.getUserId()))
                        .param("date", nextMonday.toString())
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        Patient patient = createSchedulePatient();
        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"doctorId": %d, "patientId": %d, "appointmentDateTime": "%s", "reason": "Checkup"}
                                """.formatted(doctor.getUserId(), patient.getPatientId(),
                                nextMonday.atTime(16, 0))))
                .andExpect(status().isConflict());
    }

    @Test
    void bookingWithoutConfiguredSchedule_usesDefaultDailyWindow() throws Exception {
        // no custom schedule: the clinic default (16:00-21:00 daily) governs
        Patient patient = createSchedulePatient();
        LocalDate nextMonday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));

        // inside the default window -> created
        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"doctorId": %d, "patientId": %d, "appointmentDateTime": "%s", "reason": "Checkup"}
                                """.formatted(doctor.getUserId(), patient.getPatientId(), nextMonday.atTime(16, 30))))
                .andExpect(status().isCreated());

        // outside it -> rejected
        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"doctorId": %d, "patientId": %d, "appointmentDateTime": "%s", "reason": "Checkup"}
                                """.formatted(doctor.getUserId(), patient.getPatientId(), nextMonday.atTime(9, 0))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void bookingAfterDefaultScheduleProvisioning_followsMaterialisedRows() throws Exception {
        // reading the schedule materialises the default 4 PM - 9 PM week...
        mockMvc.perform(get("/api/doctors/" + doctor.getUserId() + "/schedule")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());

        Patient patient = createSchedulePatient();
        LocalDate nextMonday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));

        // ...so bookings now run through the real schedule: inside works,
        // outside the window is a schedule conflict (409 with alternatives)
        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"doctorId": %d, "patientId": %d, "appointmentDateTime": "%s", "reason": "Checkup"}
                                """.formatted(doctor.getUserId(), patient.getPatientId(), nextMonday.atTime(17, 0))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"doctorId": %d, "patientId": %d, "appointmentDateTime": "%s", "reason": "Checkup"}
                                """.formatted(doctor.getUserId(), patient.getPatientId(), nextMonday.atTime(9, 0))))
                .andExpect(status().isConflict());
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
    void availableSlots_nearMidnightStopAtScheduleEnd() throws Exception {
        mockMvc.perform(put("/api/doctors/" + doctor.getUserId() + "/schedule")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "days": [
                                    {"dayOfWeek": "MONDAY", "timeSlots": [{"start": "23:00", "end": "23:59"}]},
                                    {"dayOfWeek": "TUESDAY", "timeSlots": []},
                                    {"dayOfWeek": "WEDNESDAY", "timeSlots": []},
                                    {"dayOfWeek": "THURSDAY", "timeSlots": []},
                                    {"dayOfWeek": "FRIDAY", "timeSlots": []},
                                    {"dayOfWeek": "SATURDAY", "timeSlots": []},
                                    {"dayOfWeek": "SUNDAY", "timeSlots": []}
                                  ]
                                }
                                """))
                .andExpect(status().isOk());

        LocalDate nextMonday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        mockMvc.perform(get("/api/appointments/available-slots")
                        .param("doctorId", String.valueOf(doctor.getUserId()))
                        .param("date", nextMonday.toString())
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0]").value("23:00"));
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

        Patient patient = createSchedulePatient();

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

    @Test
    void bookingInsideScheduleButOffGrid_returns400() throws Exception {
        // The doctor's schedule (09:00-12:00) sits outside the clinic-hours
        // stub (16:00-21:00), so it must govern booking entirely: 09:00 is
        // bookable, but an off-grid time inside the window never appears in
        // available-slots and must not be silently bookable either.
        saveValidSchedule();

        Patient patient = createSchedulePatient();
        LocalDate nextMonday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));

        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"doctorId": %d, "patientId": %d, "appointmentDateTime": "%s", "reason": "Checkup"}
                                """.formatted(doctor.getUserId(), patient.getPatientId(), nextMonday.atTime(9, 15))))
                .andExpect(status().isBadRequest());

        assertThat(appointmentRepository.count()).isZero();
    }

    private Patient createSchedulePatient() {
        Patient patient = new Patient();
        patient.setFullName("Schedule Test Patient");
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        patient.setContactNo("0771234567");
        patient.setGender(Gender.MALE);
        patient.setIsActive(true);
        return patientRepository.save(patient);
    }

    private void saveValidSchedule() throws Exception {
        mockMvc.perform(put("/api/doctors/" + doctor.getUserId() + "/schedule")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validScheduleJson()))
                .andExpect(status().isOk());
    }

    private String validScheduleJson() {
        // weekly contract: all seven days, an empty timeSlots list = day off
        return """
                {
                  "days": [
                    {"dayOfWeek": "MONDAY",    "timeSlots": [{"start": "09:00", "end": "12:00"}]},
                    {"dayOfWeek": "TUESDAY",   "timeSlots": [{"start": "09:00", "end": "12:00"}]},
                    {"dayOfWeek": "WEDNESDAY", "timeSlots": [{"start": "09:00", "end": "12:00"}]},
                    {"dayOfWeek": "THURSDAY",  "timeSlots": [{"start": "09:00", "end": "12:00"}]},
                    {"dayOfWeek": "FRIDAY",    "timeSlots": [{"start": "09:00", "end": "12:00"}]},
                    {"dayOfWeek": "SATURDAY",  "timeSlots": []},
                    {"dayOfWeek": "SUNDAY",    "timeSlots": []}
                  ]
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
