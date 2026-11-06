package com.onecare.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onecare.backend.dto.AppointmentConfirmation;
import com.onecare.backend.entity.Appointment;
import com.onecare.backend.entity.Patient;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.AppointmentStatus;
import com.onecare.backend.enums.Gender;
import com.onecare.backend.enums.Role;
import com.onecare.backend.repository.AppointmentRepository;
import com.onecare.backend.repository.InvoiceRepository;
import com.onecare.backend.repository.PasswordResetTokenRepository;
import com.onecare.backend.repository.PatientRepository;
import com.onecare.backend.repository.PrescriptionRepository;
import com.onecare.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "auth.rate-limit.enabled=false",
        "public-booking.rate-limit.enabled=false"
})
class PublicAppointmentBookingIntegrationTest {

    private static final String BOOKING_URL = "/api/public/appointments";

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
    private PrescriptionRepository prescriptionRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    /** Confirmation dispatch is stubbed (SMS/email provider not live) — replace and verify. */
    @MockitoBean
    private com.onecare.backend.service.AppointmentConfirmationNotifier confirmationNotifier;

    private User doctor;
    private LocalDateTime slot;

    @BeforeEach
    void setUp() {
        prescriptionRepository.deleteAll();
        invoiceRepository.deleteAll();
        appointmentRepository.deleteAll();
        patientRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();

        doctor = new User();
        doctor.setUsername("public-doctor");
        doctor.setEmail("public-doctor@onecare.com");
        doctor.setPasswordHash("not-a-real-login");
        doctor.setRole(Role.DOCTOR);
        doctor.setIsActive(true);
        doctor.setFailedAttempts(0);
        doctor = userRepository.save(doctor);

        slot = LocalDate.now().plusDays(2).atTime(16, 30, 0, 0);
    }

    // ---------- AC1: happy path ----------

    @Test
    void bookAppointment_happyPath_createsScheduledAppointment_andTriggersConfirmation() throws Exception {
        mockMvc.perform(post(BOOKING_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson("Nimal Perera", "0771234567",
                                LocalDate.of(1990, 5, 12), doctor.getUserId(), slot, "Male")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.data.patientFullName").value("Nimal Perera"))
                .andExpect(jsonPath("$.data.doctorId").value(doctor.getUserId().intValue()))
                .andExpect(jsonPath("$.data.appointmentDate").value(slot.toLocalDate().toString()))
                .andExpect(jsonPath("$.data.timeSlot").value("16:30:00"))
                .andExpect(jsonPath("$.data.clinicalNotes").doesNotExist());

        // persisted with status SCHEDULED (repository check)
        Appointment persisted = appointmentRepository.findAll().get(0);
        assertThat(persisted.getStatus()).isEqualTo(AppointmentStatus.SCHEDULED);
        assertThat(persisted.getDoctor().getUserId()).isEqualTo(doctor.getUserId());

        // AC1: confirmation triggered through the notifier interface
        ArgumentCaptor<AppointmentConfirmation> captor =
                ArgumentCaptor.forClass(AppointmentConfirmation.class);
        verify(confirmationNotifier).sendBookingConfirmation(captor.capture());
        assertThat(captor.getValue().appointmentId()).isEqualTo(persisted.getAppointmentId());
        assertThat(captor.getValue().patientFullName()).isEqualTo("Nimal Perera");
        assertThat(captor.getValue().patientContactNo()).isEqualTo("+94771234567"); // canonical format
        assertThat(captor.getValue().appointmentDate()).isEqualTo(slot.toLocalDate());
        assertThat(captor.getValue().timeSlot()).isEqualTo(slot.toLocalTime());
    }

    @Test
    void bookAppointment_unknownPatient_createsMinimalPatientRecord() throws Exception {
        mockMvc.perform(post(BOOKING_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson("Kamal Silva", "0712345678",
                                LocalDate.of(1985, 3, 10), doctor.getUserId(), slot, "Male")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.patientFullName").value("Kamal Silva"));

        Patient created = patientRepository.findAll().get(0);
        assertThat(created.getFullName()).isEqualTo("Kamal Silva");
        assertThat(created.getContactNo()).isEqualTo("+94712345678"); // canonical storage format
        assertThat(created.getDateOfBirth()).isEqualTo(LocalDate.of(1985, 3, 10));
        assertThat(created.getIsActive()).isTrue();
    }

    // ---------- AC3: existing patient is reused, not duplicated ----------

    @Test
    void bookAppointment_existingNameAndPhoneMatch_reusesPatientRecord() throws Exception {
        Patient existing = new Patient();
        existing.setFullName("Nimal Perera");
        existing.setDateOfBirth(LocalDate.of(1990, 5, 12));
        existing.setContactNo("+94 771 234 567"); // legacy spaced form — persisted canonically
        existing.setGender(Gender.MALE);
        existing.setIsActive(true);
        existing = patientRepository.save(existing);

        // submitted in local 10-digit form — must still match the normalized record
        mockMvc.perform(post(BOOKING_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson("nimal perera", "0771234567",
                                LocalDate.of(1990, 5, 12), doctor.getUserId(), slot, "Male")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.patientId").value(existing.getPatientId().intValue()));

        // same record reused — no duplicate patient created
        assertThat(patientRepository.count()).isEqualTo(1);
        Appointment persisted = appointmentRepository.findAll().get(0);
        assertThat(persisted.getPatient().getPatientId()).isEqualTo(existing.getPatientId());
    }

    @Test
    void bookAppointment_invalidPatientFields_return400BeforeMatchingExistingPatients() throws Exception {
        Patient existing = new Patient();
        existing.setFullName("Nimal Perera");
        existing.setDateOfBirth(LocalDate.of(1990, 5, 12));
        existing.setContactNo("+94 771 234 567");
        existing.setGender(Gender.MALE);
        existing.setIsActive(true);
        patientRepository.save(existing);

        Patient existingOtherPatient = new Patient();
        existingOtherPatient.setFullName("Kamal Silva");
        existingOtherPatient.setDateOfBirth(LocalDate.of(1985, 3, 10));
        // a malformed phone can no longer exist: chk_patient_contact_no rejects it
        existingOtherPatient.setContactNo("0712345678");
        existingOtherPatient.setGender(Gender.MALE);
        existingOtherPatient.setIsActive(true);
        patientRepository.save(existingOtherPatient);

        mockMvc.perform(post(BOOKING_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson("Nimal Perera", "0771234567",
                                LocalDate.of(1990, 5, 12), doctor.getUserId(), slot, "")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(BOOKING_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson("Nimal Perera", "0771234567",
                                LocalDate.now().plusDays(1), doctor.getUserId(), slot, "Male")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(BOOKING_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson("Kamal Silva", "not-a-phone",
                                LocalDate.of(1985, 3, 10), doctor.getUserId(), slot, "Male")))
                .andExpect(status().isBadRequest());

        assertThat(patientRepository.count()).isEqualTo(2);
        assertThat(appointmentRepository.count()).isZero();
    }

    // ---------- AC4: slot taken between page-load and submission ----------

    @Test
    void bookAppointment_slotTaken_returns409WithAlternativeSlots() throws Exception {
        Patient patient = new Patient();
        patient.setFullName("Other Patient");
        patient.setDateOfBirth(LocalDate.of(1980, 1, 1));
        patient.setContactNo("0779999888");
        patient.setGender(Gender.FEMALE);
        patient.setIsActive(true);
        patient = patientRepository.save(patient);

        Appointment taken = new Appointment();
        taken.setDoctor(doctor);
        taken.setPatient(patient);
        taken.setAppointmentDate(slot.toLocalDate());
        taken.setTimeSlot(slot.toLocalTime());
        taken.setStatus(AppointmentStatus.SCHEDULED);
        appointmentRepository.save(taken);

        String conflictResponse = mockMvc.perform(post(BOOKING_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson("Nimal Perera", "0771234567",
                                LocalDate.of(1990, 5, 12), doctor.getUserId(), slot, "Male")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.requestedSlot").value(slot.toString()))
                .andExpect(jsonPath("$.data.alternativeSlots").isArray())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // same UX contract as DDP-20: alternatives present and exclude the requested slot
        JsonNode alternatives = objectMapper.readTree(conflictResponse)
                .path("data").path("alternativeSlots");
        assertThat(alternatives.isEmpty()).isFalse();
        assertThat(alternatives.toString()).doesNotContain(slot.toString());

        // nothing was booked, and no duplicate patient was created
        assertThat(appointmentRepository.count()).isEqualTo(1);
        assertThat(patientRepository.count()).isEqualTo(1);
    }

    // ---------- Clinic grid enforcement ----------

    @Test
    void bookAppointment_outsideClinicHours_returns400() throws Exception {
        LocalDateTime beforeOpening = LocalDate.now().plusDays(2).atTime(10, 0);

        mockMvc.perform(post(BOOKING_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson("Nimal Perera", "0771234567",
                                LocalDate.of(1990, 5, 12), doctor.getUserId(), beforeOpening, "Male")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("clinic slot")));

        assertThat(appointmentRepository.count()).isZero();
    }

    @Test
    void bookAppointment_offGridMinutes_returns400() throws Exception {
        LocalDateTime insideHoursButOffGrid = LocalDate.now().plusDays(2).atTime(16, 15);

        mockMvc.perform(post(BOOKING_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson("Nimal Perera", "0771234567",
                                LocalDate.of(1990, 5, 12), doctor.getUserId(), insideHoursButOffGrid, "Male")))
                .andExpect(status().isBadRequest());

        assertThat(appointmentRepository.count()).isZero();
    }

    @Test
    void bookAppointment_lastSlotOfTheDay_accepted() throws Exception {
        // default window without a schedule: 16:00-21:00 -> last slot 20:30-21:00
        LocalDateTime lastSlot = LocalDate.now().plusDays(2).atTime(20, 30);

        mockMvc.perform(post(BOOKING_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson("Nimal Perera", "0771234567",
                                LocalDate.of(1990, 5, 12), doctor.getUserId(), lastSlot, "Male")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.timeSlot").value("20:30:00"))
                .andExpect(jsonPath("$.data.status").value("SCHEDULED"));
    }

    @Test
    void bookAppointment_afterDefaultWindowEnd_returns400() throws Exception {
        // 21:00 is the end of the default window: nothing may start at/after it
        LocalDateTime afterWindow = LocalDate.now().plusDays(2).atTime(21, 0);

        mockMvc.perform(post(BOOKING_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson("Nimal Perera", "0771234567",
                                LocalDate.of(1990, 5, 12), doctor.getUserId(), afterWindow, "Male")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("clinic slot")));

        assertThat(appointmentRepository.count()).isZero();
    }

    // ---------- Validation / security posture ----------

    @Test
    void bookAppointment_missingRequiredFields_returns400() throws Exception {
        mockMvc.perform(post(BOOKING_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed"));
    }

    @Test
    void bookAppointment_unknownDoctor_returns404() throws Exception {
        mockMvc.perform(post(BOOKING_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson("Nimal Perera", "0771234567",
                                LocalDate.of(1990, 5, 12), 999999L, slot, "Male")))
                .andExpect(status().isNotFound());
    }

    @Test
    void bookAppointment_inactiveDoctor_returns400WithoutPersistingPatientOrAppointment() throws Exception {
        doctor.setIsActive(false);
        userRepository.save(doctor);

        mockMvc.perform(post(BOOKING_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson("Nimal Perera", "0771234567",
                                LocalDate.of(1990, 5, 12), doctor.getUserId(), slot, "Male")))
                .andExpect(status().isBadRequest());

        assertThat(patientRepository.count()).isZero();
        assertThat(appointmentRepository.count()).isZero();
    }

    private String bookingJson(String fullName, String contactNo, LocalDate dateOfBirth,
            Long doctorId, LocalDateTime appointmentDateTime, String gender) {
        return """
                {"fullName":"%s","contactNo":"%s","dateOfBirth":"%s","doctorId":%d,"appointmentDateTime":"%s","gender":"%s"}
                """.formatted(fullName, contactNo, dateOfBirth, doctorId, appointmentDateTime, gender);
    }
}
