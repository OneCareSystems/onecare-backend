package com.onecare.backend.integration;

import com.onecare.backend.entity.Appointment;
import com.onecare.backend.entity.DoctorSchedule;
import com.onecare.backend.entity.Patient;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.AppointmentStatus;
import com.onecare.backend.enums.Gender;
import com.onecare.backend.enums.Role;
import com.onecare.backend.repository.AppointmentRepository;
import com.onecare.backend.repository.DoctorScheduleRepository;
import com.onecare.backend.repository.InvoiceRepository;
import com.onecare.backend.repository.PasswordResetTokenRepository;
import com.onecare.backend.repository.PatientRepository;
import com.onecare.backend.repository.PrescriptionRepository;
import com.onecare.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Public doctor discovery endpoints backing the patient self-service booking
 * form: GET /api/public/doctors and GET /api/public/doctors/{id}/available-slots.
 * Everything here runs without an Authorization header (SecurityConfig
 * permitAll on /api/public/**); the public-booking rate-limit tier is disabled
 * so the assertions exercise routing, availability and the error contract.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "auth.rate-limit.enabled=false",
        "public-booking.rate-limit.enabled=false"
})
class PublicDoctorEndpointsIntegrationTest {

    private static final String DOCTORS_URL = "/api/public/doctors";
    /** Test clinic window (application.properties): 16:00-21:00 in 30-min slots. */
    private static final String FIRST_CLINIC_SLOT = "16:00";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private DoctorScheduleRepository doctorScheduleRepository;

    @Autowired
    private PrescriptionRepository prescriptionRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    private User doctor;
    private LocalDate date;

    @BeforeEach
    void setUp() {
        prescriptionRepository.deleteAll();
        invoiceRepository.deleteAll();
        appointmentRepository.deleteAll();
        doctorScheduleRepository.deleteAll();
        patientRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();

        doctor = saveUser("Dr.Public", Role.DOCTOR, true);
        date = LocalDate.now().plusDays(2);
    }

    // ---------- GET /api/public/doctors ----------

    @Test
    void listDoctors_returnsActiveDoctorsOrderedByName_withMinimalFields() throws Exception {
        saveUser("Dr.Abeyesinghe", Role.DOCTOR, true); // sorts before Dr.Public

        mockMvc.perform(get(DOCTORS_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].displayName").value("Dr.Abeyesinghe"))
                .andExpect(jsonPath("$.data[1].displayName").value("Dr.Public"))
                .andExpect(jsonPath("$.data[0].doctorId").isNumber())
                // minimal PII: nothing beyond id + display name leaves the public API
                .andExpect(jsonPath("$.data[0].email").doesNotExist())
                .andExpect(jsonPath("$.data[0].username").doesNotExist())
                .andExpect(jsonPath("$.data[0].role").doesNotExist())
                .andExpect(jsonPath("$.data[0].isActive").doesNotExist());
    }

    @Test
    void listDoctors_excludesInactiveDoctorsAndNonDoctorUsers() throws Exception {
        saveUser("Dr.Retired", Role.DOCTOR, false);
        saveUser("admin", Role.ADMIN, true);
        saveUser("pharmacist1", Role.PHARMACIST, true);

        mockMvc.perform(get(DOCTORS_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].displayName").value("Dr.Public"));
    }

    @Test
    void listDoctors_noActiveDoctors_returnsEmptyList() throws Exception {
        doctor.setIsActive(false);
        userRepository.save(doctor);

        mockMvc.perform(get(DOCTORS_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    // ---------- GET /api/public/doctors/{id}/available-slots ----------

    @Test
    void availableSlots_futureDate_returnsClinicWindowSlots() throws Exception {
        mockMvc.perform(get(slotsUrl(doctor))
                        .param("date", date.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isNotEmpty())
                .andExpect(jsonPath("$.data[0]").value(FIRST_CLINIC_SLOT));
    }

    @Test
    void availableSlots_alreadyBookedSlotIsExcluded() throws Exception {
        Patient patient = savePatient();
        Appointment taken = new Appointment();
        taken.setDoctor(doctor);
        taken.setPatient(patient);
        taken.setAppointmentDate(date);
        taken.setTimeSlot(LocalTime.of(16, 30));
        taken.setStatus(AppointmentStatus.SCHEDULED);
        appointmentRepository.save(taken);

        String body = mockMvc.perform(get(slotsUrl(doctor))
                        .param("date", date.toString()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("16:30");
        assertThat(body).contains("16:00");
    }

    @Test
    void availableSlots_pastDate_returnsEmptyList() throws Exception {
        mockMvc.perform(get(slotsUrl(doctor))
                        .param("date", LocalDate.now().minusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void availableSlots_configuredScheduleOverridesClinicWindow() throws Exception {
        // Doctor works 09:00-11:00 on the queried day — the clinic-hours
        // fallback (16:00-21:00) must NOT apply once a schedule exists.
        doctorScheduleRepository.save(schedule(doctor, date.getDayOfWeek(),
                LocalTime.of(9, 0), LocalTime.of(11, 0), true));

        MvcResult result = mockMvc.perform(get(slotsUrl(doctor))
                        .param("date", date.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isNotEmpty())
                .andReturn();

        String slots = result.getResponse().getContentAsString();
        assertThat(slots).contains("09:00", "09:30", "10:00", "10:30");
        assertThat(slots).doesNotContain("16:00");
    }

    @Test
    void availableSlots_nonWorkingDay_returnsEmptyList() throws Exception {
        // Schedule exists but never on the queried day: non-working day.
        DayOfWeek workingDay = date.getDayOfWeek() == DayOfWeek.MONDAY
                ? DayOfWeek.TUESDAY : DayOfWeek.MONDAY;
        doctorScheduleRepository.save(schedule(doctor, workingDay,
                LocalTime.of(9, 0), LocalTime.of(11, 0), true));

        mockMvc.perform(get(slotsUrl(doctor))
                        .param("date", date.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void availableSlots_unknownDoctor_returns404() throws Exception {
        mockMvc.perform(get("/api/public/doctors/999999/available-slots")
                        .param("date", date.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void availableSlots_nonDoctorUser_returns400() throws Exception {
        User admin = saveUser("admin", Role.ADMIN, true);

        mockMvc.perform(get(slotsUrl(admin))
                        .param("date", date.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("not an active doctor")));
    }

    @Test
    void availableSlots_inactiveDoctor_returns400() throws Exception {
        doctor.setIsActive(false);
        userRepository.save(doctor);

        mockMvc.perform(get(slotsUrl(doctor))
                        .param("date", date.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("not an active doctor")));
    }

    @Test
    void availableSlots_missingDate_returns400() throws Exception {
        mockMvc.perform(get(slotsUrl(doctor)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("'date'")));
    }

    @Test
    void availableSlots_malformedDate_returns400() throws Exception {
        mockMvc.perform(get(slotsUrl(doctor))
                        .param("date", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void availableSlots_nonNumericDoctorId_returns400() throws Exception {
        mockMvc.perform(get("/api/public/doctors/abc/available-slots")
                        .param("date", date.toString()))
                .andExpect(status().isBadRequest());
    }

    // ---------- helpers ----------

    private String slotsUrl(User user) {
        return DOCTORS_URL + "/" + user.getUserId() + "/available-slots";
    }

    private User saveUser(String username, Role role, boolean active) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@onecare.test");
        user.setPasswordHash("not-a-real-login");
        user.setRole(role);
        user.setIsActive(active);
        user.setFailedAttempts(0);
        return userRepository.save(user);
    }

    private Patient savePatient() {
        Patient patient = new Patient();
        patient.setFullName("Slot Holder");
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        patient.setContactNo("0771112222");
        patient.setGender(Gender.MALE);
        patient.setIsActive(true);
        return patientRepository.save(patient);
    }

    private DoctorSchedule schedule(User user, DayOfWeek dayOfWeek,
            LocalTime start, LocalTime end, boolean available) {
        DoctorSchedule schedule = new DoctorSchedule();
        schedule.setDoctor(user);
        schedule.setDayOfWeek(dayOfWeek);
        schedule.setStartTime(start);
        schedule.setEndTime(end);
        schedule.setAvailable(available);
        return schedule;
    }
}
