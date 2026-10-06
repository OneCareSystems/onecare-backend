package com.onecare.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onecare.backend.entity.Patient;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.Gender;
import com.onecare.backend.enums.Role;
import com.onecare.backend.repository.AppointmentRepository;
import com.onecare.backend.repository.PasswordResetTokenRepository;
import com.onecare.backend.repository.PatientRepository;
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
        com.onecare.backend.entity.Appointment appointment = new com.onecare.backend.entity.Appointment();
        appointment.setDoctor(doctor);
        appointment.setPatient(patient);
        appointment.setAppointmentDate(LocalDate.now());
        appointment.setTimeSlot(timeSlot);
        appointment.setReason(reason);
        appointment.setStatus(com.onecare.backend.enums.AppointmentStatus.SCHEDULED);
        appointmentRepository.save(appointment);
    }
}
