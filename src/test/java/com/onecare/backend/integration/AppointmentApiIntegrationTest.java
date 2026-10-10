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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "auth.rate-limit.enabled=false")
class AppointmentApiIntegrationTest {

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
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String adminToken;
    private String doctorToken;
    private String pharmacistToken;
    private User doctor;
    private Patient patient;

    @BeforeEach
    void setUp() throws Exception {
        prescriptionRepository.deleteAll();
        appointmentRepository.deleteAll();
        patientRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();

        createUser("admin", "admin@onecare.com", Role.ADMIN);
        doctor = createUser("doctor", "doctor@onecare.com", Role.DOCTOR);
        createUser("pharmacist", "pharmacist@onecare.com", Role.PHARMACIST);

        patient = new Patient();
        patient.setFullName("Test Patient");
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        patient.setContactNo("0771111222");
        patient.setEmail("patient@onecare.com");
        patient.setGender(Gender.MALE);
        patient.setIsActive(true);
        patientRepository.save(patient);

        adminToken = loginAndGetToken("admin");
        doctorToken = loginAndGetToken("doctor");
        pharmacistToken = loginAndGetToken("pharmacist");
    }

    @Test
    void createAppointment_asPharmacist_returns403() throws Exception {
        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", "Bearer " + pharmacistToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(appointmentJson(doctor.getUserId(), patient.getPatientId(),
                                LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0),
                                "Follow-up")))
                .andExpect(status().isForbidden());
    }

    @Test
    void queueToday_asPharmacist_returns403() throws Exception {
        mockMvc.perform(get("/api/queue/today")
                        .header("Authorization", "Bearer " + pharmacistToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void createAppointment_conflictReturns409WithAlternatives() throws Exception {
        String body = appointmentJson(doctor.getUserId(), patient.getPatientId(),
                LocalDate.now().plusDays(1).atTime(10, 0), "Follow-up");

        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        String conflictResponse = mockMvc.perform(post("/api/appointments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.requestedSlot").isString())
                .andExpect(jsonPath("$.data.alternativeSlots").isArray())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode bodyNode = objectMapper.readTree(conflictResponse);
        assertThat(bodyNode.path("data").path("alternativeSlots").isEmpty()).isFalse();
    }

    @Test
    void queueToday_ordersAppointmentsByTime() throws Exception {
        seedQueueAppointment(doctor, patient, LocalTime.of(11, 0), "third");
        seedQueueAppointment(doctor, patient, LocalTime.of(9, 0), "first");
        seedQueueAppointment(doctor, patient, LocalTime.of(10, 0), "second");

        mockMvc.perform(get("/api/queue/today")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].timeSlot").value("09:00:00"))
                .andExpect(jsonPath("$.data[1].timeSlot").value("10:00:00"))
                .andExpect(jsonPath("$.data[2].timeSlot").value("11:00:00"));
    }

    // ---------- Clinical notes on appointments (moved from prescriptions) ----------

    @Test
    void updateAppointment_asAssignedDoctor_storesClinicalNotes() throws Exception {
        Appointment appointment = seedAppointment(doctor, patient, LocalTime.of(9, 0),
                "Checkup", null);

        mockMvc.perform(put("/api/appointments/" + appointment.getAppointmentId())
                        .header("Authorization", "Bearer " + doctorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clinicalNotes":"Severe headache, paracetamol advised"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clinicalNotes")
                        .value("Severe headache, paracetamol advised"));

        Appointment reloaded = appointmentRepository
                .findById(appointment.getAppointmentId()).orElseThrow();
        assertThat(reloaded.getClinicalNotes()).isEqualTo("Severe headache, paracetamol advised");
    }

    @Test
    void updateAppointment_asAdmin_withClinicalNotes_returns403() throws Exception {
        Appointment appointment = seedAppointment(doctor, patient, LocalTime.of(9, 0),
                "Checkup", null);

        mockMvc.perform(put("/api/appointments/" + appointment.getAppointmentId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clinicalNotes":"Admin should not write notes"}"""))
                .andExpect(status().isForbidden());

        Appointment reloaded = appointmentRepository
                .findById(appointment.getAppointmentId()).orElseThrow();
        assertThat(reloaded.getClinicalNotes()).isNull();
    }

    @Test
    void updateAppointment_nullClinicalNotes_leavesExistingNotesUntouched() throws Exception {
        Appointment appointment = seedAppointment(doctor, patient, LocalTime.of(9, 0),
                "Checkup", "Original notes");

        mockMvc.perform(put("/api/appointments/" + appointment.getAppointmentId())
                        .header("Authorization", "Bearer " + doctorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"Updated reason"}"""))
                .andExpect(status().isOk());

        Appointment reloaded = appointmentRepository
                .findById(appointment.getAppointmentId()).orElseThrow();
        assertThat(reloaded.getClinicalNotes()).isEqualTo("Original notes");
        assertThat(reloaded.getReason()).isEqualTo("Updated reason");
    }

    @Test
    void clinicalNotes_asAssignedDoctor_returns200() throws Exception {
        Appointment appointment = seedAppointment(doctor, patient, LocalTime.of(9, 0),
                "Checkup", "Severe headache");

        mockMvc.perform(get("/api/appointments/" + appointment.getAppointmentId() + "/clinical-notes")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appointmentId")
                        .value(appointment.getAppointmentId().intValue()))
                .andExpect(jsonPath("$.data.clinicalNotes").value("Severe headache"));
    }

    @Test
    void clinicalNotes_asOtherDoctor_returns403() throws Exception {
        User otherDoctor = createUser("doctor2", "doctor2@onecare.com", Role.DOCTOR);
        Appointment appointment = seedAppointment(otherDoctor, patient, LocalTime.of(9, 0),
                "Checkup", "Severe headache");

        mockMvc.perform(get("/api/appointments/" + appointment.getAppointmentId() + "/clinical-notes")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void clinicalNotes_asPharmacist_returns200() throws Exception {
        Appointment appointment = seedAppointment(doctor, patient, LocalTime.of(9, 0),
                "Checkup", "Post-op pain management");

        mockMvc.perform(get("/api/appointments/" + appointment.getAppointmentId() + "/clinical-notes")
                        .header("Authorization", "Bearer " + pharmacistToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clinicalNotes").value("Post-op pain management"));
    }

    @Test
    void clinicalNotes_asAdmin_returns403() throws Exception {
        Appointment appointment = seedAppointment(doctor, patient, LocalTime.of(9, 0),
                "Checkup", "Severe headache");

        mockMvc.perform(get("/api/appointments/" + appointment.getAppointmentId() + "/clinical-notes")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void clinicalNotes_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/appointments/1/clinical-notes"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void clinicalNotes_unknownAppointment_returns404() throws Exception {
        mockMvc.perform(get("/api/appointments/999999/clinical-notes")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void listAppointments_asDoctor_includesOwnClinicalNotes() throws Exception {
        seedAppointment(doctor, patient, LocalTime.of(9, 0), "Checkup", "Severe headache");

        mockMvc.perform(get("/api/appointments")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].clinicalNotes").value("Severe headache"));
    }

    @Test
    void listAppointments_asAdmin_omitsClinicalNotes() throws Exception {
        seedAppointment(doctor, patient, LocalTime.of(9, 0), "Checkup", "Severe headache");

        mockMvc.perform(get("/api/appointments")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].reason").value("Checkup"))
                .andExpect(jsonPath("$.data[0].clinicalNotes").doesNotExist());
    }

    // ---------- Today's appointments ----------

    @Test
    void today_asAdmin_returnsAllTodaysAppointments_excludingOtherDays() throws Exception {
        seedQueueAppointment(doctor, patient, LocalTime.of(9, 0), "morning");
        seedQueueAppointment(doctor, patient, LocalTime.of(10, 30), "later");
        seedAppointment(doctor, patient, LocalTime.of(11, 0), "tomorrow", null);

        mockMvc.perform(get("/api/appointments/today")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].timeSlot").value("09:00:00"))
                .andExpect(jsonPath("$.data[1].timeSlot").value("10:30:00"));
    }

    @Test
    void today_asDoctor_returnsOnlyOwnAppointments_withoutOthersNotes() throws Exception {
        User otherDoctor = createUser("doctor2", "doctor2@onecare.com", Role.DOCTOR);
        seedQueueAppointment(doctor, patient, LocalTime.of(9, 0), "mine");
        seedQueueAppointment(otherDoctor, patient, LocalTime.of(10, 0), "not mine");

        mockMvc.perform(get("/api/appointments/today")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].reason").value("mine"));
    }

    @Test
    void today_asDoctor_includesOwnClinicalNotes() throws Exception {
        Appointment appointment = seedAppointment(doctor, patient, LocalTime.of(9, 0),
                "mine", "Severe headache");
        appointment.setAppointmentDate(LocalDate.now());
        appointmentRepository.save(appointment);

        mockMvc.perform(get("/api/appointments/today")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].clinicalNotes").value("Severe headache"));
    }

    // ---------- Get appointment by ID (no clinical notes) ----------

    @Test
    void getById_asAssignedDoctor_returns200_withoutClinicalNotes() throws Exception {
        Appointment appointment = seedAppointment(doctor, patient, LocalTime.of(9, 0),
                "Checkup", "Severe headache");

        mockMvc.perform(get("/api/appointments/" + appointment.getAppointmentId())
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appointmentId")
                        .value(appointment.getAppointmentId().intValue()))
                .andExpect(jsonPath("$.data.reason").value("Checkup"))
                .andExpect(jsonPath("$.data.clinicalNotes").doesNotExist());
    }

    @Test
    void getById_asAdmin_returns200_withoutClinicalNotes() throws Exception {
        Appointment appointment = seedAppointment(doctor, patient, LocalTime.of(9, 0),
                "Checkup", "Severe headache");

        mockMvc.perform(get("/api/appointments/" + appointment.getAppointmentId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reason").value("Checkup"))
                .andExpect(jsonPath("$.data.clinicalNotes").doesNotExist());
    }

    @Test
    void getById_asOtherDoctor_returns403() throws Exception {
        User otherDoctor = createUser("doctor2", "doctor2@onecare.com", Role.DOCTOR);
        Appointment appointment = seedAppointment(otherDoctor, patient, LocalTime.of(9, 0),
                "Checkup", null);

        mockMvc.perform(get("/api/appointments/" + appointment.getAppointmentId())
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void getById_unknownAppointment_returns404() throws Exception {
        mockMvc.perform(get("/api/appointments/999999")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isNotFound());
    }

    // ---------- Patient history (previous notes + prescriptions) ----------

    @Test
    void history_asDoctor_returnsPreviousAppointmentsWithNotes_andPreviousPrescriptions() throws Exception {
        User otherDoctor = createUser("doctor2", "doctor2@onecare.com", Role.DOCTOR);

        // previous: completed earlier today (other doctor) — included with notes
        Appointment completedToday = seedAppointment(otherDoctor, patient, LocalTime.of(9, 0),
                "Finished consult", "Post-op pain management");
        completedToday.setStatus(AppointmentStatus.COMPLETED);
        appointmentRepository.save(completedToday);

        // previous: yesterday — included with notes
        Appointment yesterday = seedAppointment(doctor, patient, LocalTime.of(10, 0),
                "Yesterday", "Viral fever");
        yesterday.setAppointmentDate(LocalDate.now().minusDays(1));
        appointmentRepository.save(yesterday);

        // not previous: tomorrow — excluded
        seedAppointment(doctor, patient, LocalTime.of(11, 0), "Tomorrow", "Future note");
        // not previous: still scheduled today — excluded
        seedAppointment(doctor, patient, LocalTime.of(12, 0), "Later today", "Current note");

        // previous prescription (DISPENSED) — included
        seedPrescription(doctor, patient, yesterday, LocalDate.now().minusDays(1),
                PrescriptionStatus.DISPENSED);
        // current prescription (today, ISSUED) — excluded
        seedPrescription(doctor, patient, completedToday, LocalDate.now(),
                PrescriptionStatus.ISSUED);

        mockMvc.perform(get("/api/appointments/patient/" + patient.getPatientId() + "/history")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.patientId").value(patient.getPatientId().intValue()))
                .andExpect(jsonPath("$.data.appointments.length()").value(2))
                .andExpect(jsonPath("$.data.appointments[0].reason").value("Finished consult"))
                .andExpect(jsonPath("$.data.appointments[0].clinicalNotes")
                        .value("Post-op pain management"))
                .andExpect(jsonPath("$.data.appointments[1].reason").value("Yesterday"))
                .andExpect(jsonPath("$.data.appointments[1].clinicalNotes").value("Viral fever"))
                .andExpect(jsonPath("$.data.prescriptions.length()").value(1))
                .andExpect(jsonPath("$.data.prescriptions[0].status").value("DISPENSED"))
                .andExpect(jsonPath("$.data.prescriptions[0].clinicalNotes").doesNotExist());
    }

    @Test
    void history_asAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/appointments/patient/" + patient.getPatientId() + "/history")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void history_asPharmacist_returns403() throws Exception {
        mockMvc.perform(get("/api/appointments/patient/" + patient.getPatientId() + "/history")
                        .header("Authorization", "Bearer " + pharmacistToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void history_unknownPatient_returns404() throws Exception {
        mockMvc.perform(get("/api/appointments/patient/999999/history")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void history_patientWithoutEncounters_returnsEmptyLists() throws Exception {
        mockMvc.perform(get("/api/appointments/patient/" + patient.getPatientId() + "/history")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appointments").isArray())
                .andExpect(jsonPath("$.data.appointments").isEmpty())
                .andExpect(jsonPath("$.data.prescriptions").isArray())
                .andExpect(jsonPath("$.data.prescriptions").isEmpty());
    }

    private void seedPrescription(User doctor, Patient patient, Appointment appointment,
                                  LocalDate date, PrescriptionStatus status) {
        Prescription prescription = new Prescription();
        prescription.setDoctor(doctor);
        prescription.setPatient(patient);
        prescription.setAppointment(appointment);
        prescription.setDate(date);
        prescription.setStatus(status);
        prescriptionRepository.save(prescription);
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
        JsonNode data = body.get("data");
        assertThat(data).isNotNull();
        return data.get("accessToken").asText();
    }

    private String appointmentJson(Long doctorId, Long patientId, LocalDateTime appointmentDateTime, String reason) {
        return """
                {"doctorId":%d,"patientId":%d,"appointmentDateTime":"%s","reason":"%s"}
                """.formatted(doctorId, patientId, appointmentDateTime, reason);
    }

    private void seedQueueAppointment(User doctor, Patient patient, LocalTime timeSlot, String reason) {
        Appointment appointment = new Appointment();
        appointment.setDoctor(doctor);
        appointment.setPatient(patient);
        appointment.setAppointmentDate(LocalDate.now());
        appointment.setTimeSlot(timeSlot);
        appointment.setReason(reason);
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        appointmentRepository.save(appointment);
    }

    private Appointment seedAppointment(User doctor, Patient patient, LocalTime timeSlot,
                                        String reason, String clinicalNotes) {
        Appointment appointment = new Appointment();
        appointment.setDoctor(doctor);
        appointment.setPatient(patient);
        appointment.setAppointmentDate(LocalDate.now().plusDays(1));
        appointment.setTimeSlot(timeSlot);
        appointment.setReason(reason);
        appointment.setClinicalNotes(clinicalNotes);
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        return appointmentRepository.save(appointment);
    }
}
