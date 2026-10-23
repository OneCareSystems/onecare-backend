package com.onecare.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onecare.backend.entity.Appointment;
import com.onecare.backend.entity.Patient;
import com.onecare.backend.entity.Prescription;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.AppointmentStatus;
import com.onecare.backend.enums.Gender;
import com.onecare.backend.enums.PrescriptionStatus;
import com.onecare.backend.enums.Role;
import com.onecare.backend.repository.AppointmentRepository;
import com.onecare.backend.repository.AuditLogRepository;
import com.onecare.backend.repository.DoctorScheduleRepository;
import com.onecare.backend.repository.ExternalDispensingRepository;
import com.onecare.backend.repository.InvoiceRepository;
import com.onecare.backend.repository.PatientRepository;
import com.onecare.backend.repository.PrescriptionRepository;
import com.onecare.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC1 (FR015): an authenticated Doctor requesting appointments and
 * prescriptions gets back only records with doctor_id = self — and the filter
 * is applied IN THE REPOSITORY QUERY, not after fetching. The @SpyBean
 * verification proves the service never falls back to findAll() on the
 * doctor path; the JSON assertions prove the visible result.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "auth.rate-limit.enabled=false")
class DoctorOwnDataScopingIntegrationTest {

    private static final String TEST_PASSWORD = "Password@123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PatientRepository patientRepository;

    @SpyBean
    private AppointmentRepository appointmentRepository;

    @SpyBean
    private PrescriptionRepository prescriptionRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private DoctorScheduleRepository doctorScheduleRepository;

    @Autowired
    private ExternalDispensingRepository externalDispensingRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String doctorAToken;

    private Appointment appointmentA;
    private Appointment appointmentB;
    private Prescription prescriptionA;
    private Prescription prescriptionB;

    @BeforeEach
    void setup() throws Exception {
        externalDispensingRepository.deleteAll();
        prescriptionRepository.deleteAll();
        invoiceRepository.deleteAll();
        appointmentRepository.deleteAll();
        patientRepository.deleteAll();
        auditLogRepository.deleteAll();
        doctorScheduleRepository.deleteAll();
        userRepository.deleteAll();

        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        User doctorA = createUser("scope-doctor-a", "scope-a@onecare.com", passwordHash);
        User doctorB = createUser("scope-doctor-b", "scope-b@onecare.com", passwordHash);
        createUser("scope-admin", "scope-admin@onecare.com", passwordHash);

        Patient patient = new Patient();
        patient.setFullName("Scope Patient");
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        patient.setContactNo("0779876543");
        patient.setEmail("scope-patient@onecare.com");
        patient.setGender(Gender.MALE);
        patient.setIsActive(true);
        patient = patientRepository.save(patient);

        appointmentA = seedAppointment(doctorA, patient, LocalTime.of(16, 0));
        appointmentB = seedAppointment(doctorB, patient, LocalTime.of(17, 0));

        prescriptionA = seedPrescription(doctorA, patient, appointmentA);
        prescriptionB = seedPrescription(doctorB, patient, appointmentB);

        doctorAToken = loginAndGetToken("scope-doctor-a");
    }

    @Test
    void doctorAppointments_returnOnlyOwnRecords() throws Exception {
        String body = mockMvc.perform(get("/api/appointments")
                        .header("Authorization", "Bearer " + doctorAToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode data = objectMapper.readTree(body).get("data");
        assertThat(data.isArray()).isTrue();
        assertThat(data).hasSize(1);
        assertThat(data.get(0).get("appointmentId").asLong())
                .isEqualTo(appointmentA.getAppointmentId());

        // The doctor_id = self filter ran in the repository query — the
        // service never fetched the full table to filter it afterwards (AC1).
        verify(appointmentRepository, never()).findAll();
    }

    @Test
    void doctorPrescriptions_returnOnlyOwnRecords() throws Exception {
        String body = mockMvc.perform(get("/api/prescriptions")
                        .header("Authorization", "Bearer " + doctorAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].prescriptionId")
                        .value(prescriptionA.getPrescriptionId().intValue()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode data = objectMapper.readTree(body).get("data");
        assertThat(data).hasSize(1);

        // Same proof at the prescription repository: scoped query, no fetch-all.
        verify(prescriptionRepository, never()).findAll();
        verify(prescriptionRepository, never()).findByStatus(ArgumentMatchers.any());
    }

    @Test
    void doctorTodaysPrescriptions_returnOnlyOwnRecords() throws Exception {
        mockMvc.perform(get("/api/prescriptions/today")
                        .header("Authorization", "Bearer " + doctorAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].prescriptionId")
                        .value(prescriptionA.getPrescriptionId().intValue()));

        verify(prescriptionRepository, never()).findByDateAndStatusNot(
                ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private User createUser(String username, String email, String passwordHash) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordHash);
        user.setRole(username.endsWith("admin") ? Role.ADMIN : Role.DOCTOR);
        user.setIsActive(true);
        user.setFailedAttempts(0);
        return userRepository.save(user);
    }

    private Appointment seedAppointment(User doctor, Patient patient, LocalTime slot) {
        Appointment appointment = new Appointment();
        appointment.setPatient(patient);
        appointment.setDoctor(doctor);
        appointment.setAppointmentDate(LocalDate.now().plusDays(1));
        appointment.setTimeSlot(slot);
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        appointment.setReason("Scoping probe");
        appointment.setClinicalNotes("Doctor-private notes");
        return appointmentRepository.save(appointment);
    }

    private Prescription seedPrescription(User doctor, Patient patient, Appointment appointment) {
        Prescription prescription = new Prescription();
        prescription.setDoctor(doctor);
        prescription.setPatient(patient);
        prescription.setAppointment(appointment);
        prescription.setDate(LocalDate.now());
        prescription.setStatus(PrescriptionStatus.ISSUED);
        return prescriptionRepository.save(prescription);
    }

    private String loginAndGetToken(String username) throws Exception {
        String responseBody = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"username":"%s","password":"%s"}
                                        """.formatted(username, TEST_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode data = objectMapper.readTree(responseBody).get("data");
        assertThat(data).isNotNull();
        return data.get("accessToken").asText();
    }
}
