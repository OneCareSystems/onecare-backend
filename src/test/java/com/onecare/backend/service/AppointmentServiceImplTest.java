package com.onecare.backend.service;

import com.onecare.backend.dto.request.CreateAppointmentRequest;
import com.onecare.backend.dto.request.UpdateAppointmentRequest;
import com.onecare.backend.entity.Appointment;
import com.onecare.backend.entity.Patient;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.AppointmentStatus;
import com.onecare.backend.enums.Role;
import com.onecare.backend.exception.InvalidAppointmentStatusException;
import com.onecare.backend.exception.ResourceNotFoundException;
import com.onecare.backend.repository.AppointmentRepository;
import com.onecare.backend.repository.PatientRepository;
import com.onecare.backend.repository.PrescriptionRepository;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.security.AppUserDetailsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppointmentServiceImplTest {

    private AppointmentRepository appointmentRepository;
    private PatientRepository patientRepository;
    private UserRepository userRepository;
    private PrescriptionRepository prescriptionRepository;
    private AppointmentServiceImpl appointmentService;

    @BeforeEach
    void setUp() {
        appointmentRepository = mock(AppointmentRepository.class);
        patientRepository = mock(PatientRepository.class);
        userRepository = mock(UserRepository.class);
        prescriptionRepository = mock(PrescriptionRepository.class);
        appointmentService = new AppointmentServiceImpl(
                appointmentRepository, patientRepository, userRepository, prescriptionRepository);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createAppointment_allowsSelfServicePatientWhenEmailMatches() {
        User currentUser = new User();
        currentUser.setUserId(10L);
        currentUser.setEmail("alice@example.com");
        currentUser.setRole(Role.PHARMACIST);

        appUserAuthentication(currentUser, "ROLE_PHARMACIST");

        Patient patient = new Patient();
        patient.setPatientId(30L);
        patient.setEmail("alice@example.com");

        User doctor = new User();
        doctor.setUserId(7L);
        doctor.setRole(Role.DOCTOR);

        LocalDateTime requested = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        when(userRepository.findById(10L)).thenReturn(Optional.of(currentUser));
        when(patientRepository.findById(30L)).thenReturn(Optional.of(patient));
        when(userRepository.findById(7L)).thenReturn(Optional.of(doctor));
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(doctor));
        when(appointmentRepository.findByDoctor_UserIdAndAppointmentDate(7L, requested.toLocalDate()))
                .thenReturn(List.of());
        when(appointmentRepository.saveAndFlush(any(Appointment.class))).thenAnswer(invocation -> {
            Appointment appointment = invocation.getArgument(0);
            appointment.setAppointmentId(99L);
            return appointment;
        });
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(invocation -> {
            Appointment appointment = invocation.getArgument(0);
            appointment.setAppointmentId(99L);
            return appointment;
        });

        var response = appointmentService.createAppointment(new CreateAppointmentRequest(
                7L,
                30L,
                requested,
                "Follow-up",
                null));

        assertEquals(99L, response.appointmentId());
        assertEquals(AppointmentStatus.SCHEDULED.name(), response.status());
        verify(appointmentRepository).saveAndFlush(any(Appointment.class));
    }

    @Test
    void updateAppointment_rejectsPatientSwitchForSelfServiceUser() {
        User currentUser = new User();
        currentUser.setUserId(10L);
        currentUser.setEmail("alice@example.com");
        currentUser.setRole(Role.PHARMACIST);

        appUserAuthentication(currentUser, "ROLE_PHARMACIST");

        Patient currentPatient = new Patient();
        currentPatient.setPatientId(30L);
        currentPatient.setEmail("alice@example.com");

        Patient otherPatient = new Patient();
        otherPatient.setPatientId(31L);
        otherPatient.setEmail("mallory@example.com");

        User doctor = new User();
        doctor.setUserId(7L);
        doctor.setRole(Role.DOCTOR);

        Appointment appointment = new Appointment();
        appointment.setAppointmentId(1L);
        appointment.setPatient(currentPatient);
        appointment.setDoctor(doctor);
        appointment.setAppointmentDate(LocalDate.now().plusDays(1));
        appointment.setTimeSlot(LocalTime.of(9, 0));
        appointment.setStatus(AppointmentStatus.SCHEDULED);

        when(userRepository.findById(10L)).thenReturn(Optional.of(currentUser));
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(appointment));
        when(userRepository.findById(7L)).thenReturn(Optional.of(doctor));
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(doctor));
        when(patientRepository.findById(31L)).thenReturn(Optional.of(otherPatient));
        when(patientRepository.findAll()).thenReturn(List.of(currentPatient, otherPatient));
        when(appointmentRepository.saveAndFlush(any(Appointment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var request = new UpdateAppointmentRequest(
                7L,
                31L,
                LocalDateTime.now().plusDays(2).withHour(11).withMinute(0).withSecond(0).withNano(0),
                "Rebook for a different patient",
                null);

        assertThrows(AccessDeniedException.class, () -> appointmentService.updateAppointment(1L, request));
        verify(patientRepository, never()).save(any());
    }

    @Test
    void cancelAppointment_blocksCompletedAppointment() {
        User currentUser = new User();
        currentUser.setUserId(10L);
        currentUser.setEmail("alice@example.com");
        currentUser.setRole(Role.PHARMACIST);

        appUserAuthentication(currentUser, "ROLE_PHARMACIST");

        Patient patient = new Patient();
        patient.setPatientId(30L);
        patient.setEmail("alice@example.com");

        User doctor = new User();
        doctor.setUserId(7L);
        doctor.setRole(Role.DOCTOR);

        Appointment appointment = new Appointment();
        appointment.setAppointmentId(1L);
        appointment.setPatient(patient);
        appointment.setDoctor(doctor);
        appointment.setAppointmentDate(LocalDate.now().plusDays(1));
        appointment.setTimeSlot(LocalTime.of(9, 0));
        appointment.setStatus(AppointmentStatus.COMPLETED);

        when(userRepository.findById(10L)).thenReturn(Optional.of(currentUser));
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(appointment));
        when(patientRepository.findAll()).thenReturn(List.of(patient));

        assertThrows(InvalidAppointmentStatusException.class, () -> appointmentService.cancelAppointment(1L));
    }

    // ---------- Clinical notes ----------

    @Test
    void updateAppointment_clinicalNotes_withoutWritePermission_rejected() {
        User currentUser = new User();
        currentUser.setUserId(10L);
        currentUser.setEmail("alice@example.com");
        currentUser.setRole(Role.PHARMACIST);

        appUserAuthentication(currentUser, "ROLE_PHARMACIST", "APPOINTMENT_UPDATE");

        Patient patient = new Patient();
        patient.setPatientId(30L);
        patient.setEmail("alice@example.com");

        User doctor = new User();
        doctor.setUserId(7L);
        doctor.setRole(Role.DOCTOR);

        Appointment appointment = new Appointment();
        appointment.setAppointmentId(1L);
        appointment.setPatient(patient);
        appointment.setDoctor(doctor);
        appointment.setAppointmentDate(LocalDate.now().plusDays(1));
        appointment.setTimeSlot(LocalTime.of(9, 0));
        appointment.setStatus(AppointmentStatus.SCHEDULED);

        when(userRepository.findById(10L)).thenReturn(Optional.of(currentUser));
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(appointment));
        when(patientRepository.findAll()).thenReturn(List.of(patient));

        var request = new UpdateAppointmentRequest(null, null, null, null, "Notes");

        assertThrows(AccessDeniedException.class, () -> appointmentService.updateAppointment(1L, request));
        assertNull(appointment.getClinicalNotes());
    }

    @Test
    void updateAppointment_clinicalNotes_byAssignedDoctor_persists() {
        User doctor = new User();
        doctor.setUserId(7L);
        doctor.setUsername("dr.test");
        doctor.setEmail("dr.test@onecare.test");
        doctor.setRole(Role.DOCTOR);

        Patient patient = new Patient();
        patient.setPatientId(30L);

        Appointment appointment = new Appointment();
        appointment.setAppointmentId(1L);
        appointment.setPatient(patient);
        appointment.setDoctor(doctor);
        appointment.setAppointmentDate(LocalDate.now().plusDays(1));
        appointment.setTimeSlot(LocalTime.of(9, 0));
        appointment.setStatus(AppointmentStatus.SCHEDULED);

        appUserAuthentication(doctor, "ROLE_DOCTOR", "APPOINTMENT_UPDATE",
                "APPOINTMENT_WRITE_CLINICAL_NOTES", "APPOINTMENT_READ_CLINICAL_NOTES");

        when(userRepository.findById(7L)).thenReturn(Optional.of(doctor));
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(appointment));
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(doctor));
        when(appointmentRepository.saveAndFlush(any(Appointment.class))).thenAnswer(i -> i.getArgument(0));

        var request = new UpdateAppointmentRequest(null, null, null, null, "Severe headache");

        var response = appointmentService.updateAppointment(1L, request);

        assertEquals("Severe headache", appointment.getClinicalNotes());
        assertEquals("Severe headache", response.clinicalNotes());
    }

    @Test
    void getClinicalNotes_assignedDoctor_readsNotes() {
        User doctor = new User();
        doctor.setUserId(7L);
        doctor.setUsername("dr.test");
        doctor.setRole(Role.DOCTOR);

        Patient patient = new Patient();
        patient.setPatientId(30L);

        Appointment appointment = new Appointment();
        appointment.setAppointmentId(1L);
        appointment.setPatient(patient);
        appointment.setDoctor(doctor);
        appointment.setClinicalNotes("Severe headache");

        appUserAuthentication(doctor, "ROLE_DOCTOR", "APPOINTMENT_READ_CLINICAL_NOTES");

        when(userRepository.findById(7L)).thenReturn(Optional.of(doctor));
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(appointment));

        var response = appointmentService.getClinicalNotes(1L);

        assertEquals(1L, response.appointmentId());
        assertEquals("Severe headache", response.clinicalNotes());
    }

    @Test
    void getClinicalNotes_anotherDoctorsAppointment_denied() {
        User doctor = new User();
        doctor.setUserId(7L);
        doctor.setUsername("dr.test");
        doctor.setRole(Role.DOCTOR);

        User otherDoctor = new User();
        otherDoctor.setUserId(8L);
        otherDoctor.setRole(Role.DOCTOR);

        Patient patient = new Patient();
        patient.setPatientId(30L);

        Appointment appointment = new Appointment();
        appointment.setAppointmentId(1L);
        appointment.setPatient(patient);
        appointment.setDoctor(otherDoctor);
        appointment.setClinicalNotes("Severe headache");

        appUserAuthentication(doctor, "ROLE_DOCTOR", "APPOINTMENT_READ_CLINICAL_NOTES");

        when(userRepository.findById(7L)).thenReturn(Optional.of(doctor));
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(appointment));

        assertThrows(AccessDeniedException.class, () -> appointmentService.getClinicalNotes(1L));
    }

    @Test
    void getClinicalNotes_pharmacist_readsAnyAppointment() {
        User pharmacist = new User();
        pharmacist.setUserId(9L);
        pharmacist.setUsername("pharma.test");
        pharmacist.setRole(Role.PHARMACIST);

        User doctor = new User();
        doctor.setUserId(7L);
        doctor.setRole(Role.DOCTOR);

        Patient patient = new Patient();
        patient.setPatientId(30L);

        Appointment appointment = new Appointment();
        appointment.setAppointmentId(1L);
        appointment.setPatient(patient);
        appointment.setDoctor(doctor);
        appointment.setClinicalNotes("Post-op pain management");

        appUserAuthentication(pharmacist, "ROLE_PHARMACIST", "APPOINTMENT_READ_CLINICAL_NOTES");

        when(userRepository.findById(9L)).thenReturn(Optional.of(pharmacist));
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(appointment));

        var response = appointmentService.getClinicalNotes(1L);

        assertEquals("Post-op pain management", response.clinicalNotes());
    }

    @Test
    void getClinicalNotes_admin_denied() {
        User admin = new User();
        admin.setUserId(5L);
        admin.setUsername("admin.test");
        admin.setRole(Role.ADMIN);

        Patient patient = new Patient();
        patient.setPatientId(30L);

        Appointment appointment = new Appointment();
        appointment.setAppointmentId(1L);
        appointment.setPatient(patient);
        appointment.setClinicalNotes("Severe headache");

        appUserAuthentication(admin, "ROLE_ADMIN");

        when(userRepository.findById(5L)).thenReturn(Optional.of(admin));
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(appointment));

        assertThrows(AccessDeniedException.class, () -> appointmentService.getClinicalNotes(1L));
    }

    @Test
    void getClinicalNotes_unknownAppointment_throwsNotFound() {
        User doctor = new User();
        doctor.setUserId(7L);
        doctor.setUsername("dr.test");
        doctor.setRole(Role.DOCTOR);

        appUserAuthentication(doctor, "ROLE_DOCTOR", "APPOINTMENT_READ_CLINICAL_NOTES");

        when(userRepository.findById(7L)).thenReturn(Optional.of(doctor));
        when(appointmentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> appointmentService.getClinicalNotes(99L));
    }

    // ---------- Today / By ID ----------

    @Test
    void findTodaysAppointments_adminSeesOnlyTodaysAppointments() {
        User admin = new User();
        admin.setUserId(5L);
        admin.setUsername("admin.test");
        admin.setRole(Role.ADMIN);

        appUserAuthentication(admin, "ROLE_ADMIN");

        Appointment today = new Appointment();
        today.setAppointmentId(1L);
        today.setAppointmentDate(LocalDate.now());
        today.setTimeSlot(LocalTime.of(9, 0));
        today.setStatus(AppointmentStatus.SCHEDULED);

        Appointment tomorrow = new Appointment();
        tomorrow.setAppointmentId(2L);
        tomorrow.setAppointmentDate(LocalDate.now().plusDays(1));
        tomorrow.setTimeSlot(LocalTime.of(10, 0));
        tomorrow.setStatus(AppointmentStatus.SCHEDULED);

        when(userRepository.findById(5L)).thenReturn(Optional.of(admin));
        when(appointmentRepository.findAll()).thenReturn(List.of(today, tomorrow));

        var response = appointmentService.findTodaysAppointments();

        assertEquals(1, response.size());
        assertEquals(1L, response.get(0).appointmentId());
        assertNull(response.get(0).clinicalNotes());
    }

    @Test
    void getAppointmentById_ownAppointment_excludesClinicalNotes() {
        User doctor = new User();
        doctor.setUserId(7L);
        doctor.setUsername("dr.test");
        doctor.setRole(Role.DOCTOR);

        Patient patient = new Patient();
        patient.setPatientId(30L);

        Appointment appointment = new Appointment();
        appointment.setAppointmentId(1L);
        appointment.setPatient(patient);
        appointment.setDoctor(doctor);
        appointment.setReason("Checkup");
        appointment.setClinicalNotes("Severe headache");
        appointment.setStatus(AppointmentStatus.SCHEDULED);

        appUserAuthentication(doctor, "ROLE_DOCTOR", "APPOINTMENT_READ",
                "APPOINTMENT_READ_CLINICAL_NOTES");

        when(userRepository.findById(7L)).thenReturn(Optional.of(doctor));
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(appointment));

        var response = appointmentService.getAppointmentById(1L);

        assertEquals(1L, response.appointmentId());
        assertEquals("Checkup", response.reason());
        assertNull(response.clinicalNotes());
    }

    @Test
    void getPatientHistory_unknownPatient_throwsNotFound() {
        User doctor = new User();
        doctor.setUserId(7L);
        doctor.setUsername("dr.test");
        doctor.setRole(Role.DOCTOR);

        appUserAuthentication(doctor, "ROLE_DOCTOR");

        when(userRepository.findById(7L)).thenReturn(Optional.of(doctor));
        when(patientRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> appointmentService.getPatientHistory(99L));
    }

    private void appUserAuthentication(User user, String... authorities) {
        var granted = java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList();

        var principal = new AppUserDetailsService.AppUserDetails(
                user.getUserId(),
                user.getUsername() != null ? user.getUsername() : "alice",
                "password",
                true,
                true,
                true,
                true,
                granted);

        var authentication = new UsernamePasswordAuthenticationToken(
                principal,
                "password",
                granted);

        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
