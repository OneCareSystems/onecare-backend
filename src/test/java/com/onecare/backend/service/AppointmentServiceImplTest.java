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
    private AppointmentServiceImpl appointmentService;

    @BeforeEach
    void setUp() {
        appointmentRepository = mock(AppointmentRepository.class);
        patientRepository = mock(PatientRepository.class);
        userRepository = mock(UserRepository.class);
        appointmentService = new AppointmentServiceImpl(
                appointmentRepository, patientRepository, userRepository);
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

        LocalDateTime requested = LocalDateTime.now().plusDays(2).withHour(16).withMinute(30).withSecond(0).withNano(0);
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
                "Follow-up"));

        assertEquals(99L, response.appointmentId());
        assertEquals(AppointmentStatus.SCHEDULED.name(), response.status());
        verify(appointmentRepository).saveAndFlush(any(Appointment.class));
    }

    @Test
    void cancelAppointment_blocksCompletedAppointment() {
        User currentUser = new User();
        currentUser.setUserId(10L);
        currentUser.setRole(Role.DOCTOR);

        appUserAuthentication(currentUser, "ROLE_DOCTOR");

        Patient patient = new Patient();
        patient.setPatientId(30L);
        patient.setEmail("alice@example.com");

        User doctor = new User();
        doctor.setUserId(10L);
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
    void updateAppointment_clinicalNotes_cancelledAppointment_rejected() {
        // Arrange: authenticated doctor who owns the appointment
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
        appointment.setStatus(AppointmentStatus.CANCELLED);

        appUserAuthentication(
                doctor,
                "ROLE_DOCTOR",
                "APPOINTMENT_UPDATE",
                "APPOINTMENT_WRITE_CLINICAL_NOTES",
                "APPOINTMENT_READ_CLINICAL_NOTES");

        when(userRepository.findById(7L))
                .thenReturn(Optional.of(doctor));
        when(appointmentRepository.findById(1L))
                .thenReturn(Optional.of(appointment));

        UpdateAppointmentRequest request =
                new UpdateAppointmentRequest(
                        null, null, null, null, "Patient reported symptoms.");

        // Act & Assert: cancelled appointment rejects clinical notes
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> appointmentService.updateAppointment(1L, request));

        assertEquals(
                "Clinical notes cannot be added to a cancelled appointment",
                exception.getMessage());

        // Verify: existing appointment remains unchanged and is not saved
        assertNull(appointment.getClinicalNotes());

        verify(appointmentRepository, never())
                .saveAndFlush(any(Appointment.class));
        verify(appointmentRepository, never())
                .save(any(Appointment.class));
    }


    @Test
    void updateAppointment_cancelledAppointment_withoutClinicalNotes_preservesExistingBehavior() {
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
        appointment.setAppointmentDate(LocalDate.now().plusDays(1));
        appointment.setTimeSlot(LocalTime.of(9, 0));
        appointment.setReason("Original reason");
        appointment.setStatus(AppointmentStatus.CANCELLED);

        appUserAuthentication(
                doctor,
                "ROLE_DOCTOR",
                "APPOINTMENT_UPDATE");

        when(userRepository.findById(7L))
                .thenReturn(Optional.of(doctor));
        when(userRepository.findByIdForUpdate(7L))
                .thenReturn(Optional.of(doctor));
        when(appointmentRepository.findById(1L))
                .thenReturn(Optional.of(appointment));
        when(appointmentRepository.findByDoctor_UserIdAndAppointmentDate(
                eq(7L), any(LocalDate.class)))
                .thenReturn(List.of());
        when(appointmentRepository.saveAndFlush(any(Appointment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UpdateAppointmentRequest request =
                new UpdateAppointmentRequest(
                        null, null, null, "Updated reason", null);

        var response =
                appointmentService.updateAppointment(1L, request);

        assertEquals("Updated reason", appointment.getReason());
        assertEquals(AppointmentStatus.CANCELLED.name(), response.status());
        assertNull(appointment.getClinicalNotes());

        verify(appointmentRepository).saveAndFlush(appointment);
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
    void cancelAppointment_blocksNoShowAppointment() {
        User doctor = new User();
        doctor.setUserId(7L);
        doctor.setRole(Role.DOCTOR);
        appUserAuthentication(doctor, "ROLE_DOCTOR");
        Appointment appointment = new Appointment();
        appointment.setAppointmentId(2L);
        appointment.setDoctor(doctor);
        appointment.setAppointmentDate(LocalDate.now().minusDays(1));
        appointment.setTimeSlot(LocalTime.of(9, 0));
        appointment.setStatus(AppointmentStatus.NO_SHOW);

        when(userRepository.findById(7L)).thenReturn(Optional.of(doctor));
        when(appointmentRepository.findById(2L)).thenReturn(Optional.of(appointment));

        assertThrows(InvalidAppointmentStatusException.class,
                () -> appointmentService.cancelAppointment(2L));
    }

    @Test
    void getAvailableSlots_returnsSlotsExcludingBooked() {
        User doctor = new User();
        doctor.setUserId(7L);
        doctor.setRole(Role.DOCTOR);
        LocalDate futureDate = LocalDate.now().plusDays(3);

        Appointment booked = new Appointment();
        booked.setAppointmentId(10L);
        booked.setDoctor(doctor);
        booked.setAppointmentDate(futureDate);
        booked.setTimeSlot(LocalTime.of(16, 30));
        booked.setStatus(AppointmentStatus.SCHEDULED);
        Appointment cancelled = new Appointment();
        cancelled.setAppointmentId(11L);
        cancelled.setDoctor(doctor);
        cancelled.setAppointmentDate(futureDate);
        cancelled.setTimeSlot(LocalTime.of(17, 0));
        cancelled.setStatus(AppointmentStatus.CANCELLED);

        when(userRepository.findById(7L)).thenReturn(Optional.of(doctor));
        when(appointmentRepository.findByDoctor_UserIdAndAppointmentDate(7L, futureDate))
                .thenReturn(List.of(booked, cancelled));

        List<String> available = appointmentService.getAvailableSlots(7L, futureDate);
        assertTrue(available.contains("16:00"));
        assertFalse(available.contains("16:30"));
        assertTrue(available.contains("17:00"));
        assertTrue(available.contains("20:30"));
        assertEquals(9, available.size());
    }

    @Test
    void getAvailableSlots_pastDate_returnsEmptyList() {
        LocalDate pastDate = LocalDate.now().minusDays(1);
        User doctor = new User();
        doctor.setUserId(7L);
        doctor.setRole(Role.DOCTOR);
        when(userRepository.findById(7L)).thenReturn(Optional.of(doctor));

        assertTrue(appointmentService.getAvailableSlots(7L, pastDate).isEmpty());
    }

    @Test
    void getAvailableSlots_nonDoctor_throwsException() {
        User nonDoctor = new User();
        nonDoctor.setUserId(8L);
        nonDoctor.setRole(Role.ADMIN);
        when(userRepository.findById(8L)).thenReturn(Optional.of(nonDoctor));

        assertThrows(IllegalArgumentException.class,
                () -> appointmentService.getAvailableSlots(8L, LocalDate.now().plusDays(1)));
    }

    // ---------- Public booking creation path (DDP-33 / AC1, AC4) ----------

    @Test
    void createAppointmentForPatient_createsScheduledAppointment_withoutAuthContext() {
        Patient patient = new Patient();
        patient.setPatientId(30L);
        patient.setFullName("Nimal Perera");
        patient.setContactNo("0771234567");

        User doctor = new User();
        doctor.setUserId(7L);
        doctor.setRole(Role.DOCTOR);

        LocalDateTime requested = LocalDate.now().plusDays(2).atTime(16, 30, 0, 0);

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

        var response = appointmentService.createAppointmentForPatient(30L, 7L, requested, null);

        assertEquals(99L, response.appointmentId());
        assertEquals(AppointmentStatus.SCHEDULED.name(), response.status());
        assertNull(response.clinicalNotes()); // public response never exposes clinical notes
        assertEquals(requested.toLocalDate(), response.appointmentDate());
        assertEquals(requested.toLocalTime(), response.timeSlot());
        verify(appointmentRepository).saveAndFlush(any(Appointment.class));
    }

    @Test
    void createAppointmentForPatient_slotTaken_throwsSlotConflictWithAlternatives() {
        Patient patient = new Patient();
        patient.setPatientId(30L);

        User doctor = new User();
        doctor.setUserId(7L);
        doctor.setRole(Role.DOCTOR);

        LocalDateTime requested = LocalDate.now().plusDays(2).atTime(16, 30, 0, 0);

        Appointment existing = new Appointment();
        existing.setAppointmentId(1L);
        existing.setDoctor(doctor);
        existing.setPatient(patient);
        existing.setAppointmentDate(requested.toLocalDate());
        existing.setTimeSlot(requested.toLocalTime());
        existing.setStatus(AppointmentStatus.SCHEDULED);

        when(patientRepository.findById(30L)).thenReturn(Optional.of(patient));
        when(userRepository.findById(7L)).thenReturn(Optional.of(doctor));
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(doctor));
        when(appointmentRepository.findByDoctor_UserIdAndAppointmentDate(7L, requested.toLocalDate()))
                .thenReturn(List.of(existing));

        var exception = assertThrows(com.onecare.backend.exception.SlotConflictException.class,
                () -> appointmentService.createAppointmentForPatient(30L, 7L, requested, null));

        assertEquals(requested.toString(), exception.getRequestedSlot());
        assertFalse(exception.getAlternativeSlots().isEmpty());
        assertFalse(exception.getAlternativeSlots().contains(requested.toString()));
        verify(appointmentRepository, never()).saveAndFlush(any(Appointment.class));
    }

    @Test
    void createAppointmentForPatient_unknownPatient_throwsNotFound() {
        when(patientRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> appointmentService.createAppointmentForPatient(
                        999L, 7L, LocalDate.now().plusDays(1).atTime(16, 30), null));
    }

    @Test
    void createAppointmentForPatient_nonDoctorTarget_rejected() {
        Patient patient = new Patient();
        patient.setPatientId(30L);

        User nonDoctor = new User();
        nonDoctor.setUserId(7L);
        nonDoctor.setRole(Role.ADMIN);

        when(patientRepository.findById(30L)).thenReturn(Optional.of(patient));
        when(userRepository.findById(7L)).thenReturn(Optional.of(nonDoctor));

        assertThrows(IllegalArgumentException.class,
                () -> appointmentService.createAppointmentForPatient(
                        30L, 7L, LocalDate.now().plusDays(1).atTime(16, 30), null));
    }

    // ---------- Clinic grid enforcement (booking must land on an operating-hours slot) ----------

    @Test
    void createAppointmentForPatient_outsideClinicHours_rejected() {
        Patient patient = new Patient();
        patient.setPatientId(30L);

        User doctor = new User();
        doctor.setUserId(7L);
        doctor.setRole(Role.DOCTOR);

        LocalDateTime beforeOpening = LocalDate.now().plusDays(2).atTime(10, 0);

        when(patientRepository.findById(30L)).thenReturn(Optional.of(patient));
        when(userRepository.findById(7L)).thenReturn(Optional.of(doctor));

        assertThrows(com.onecare.backend.exception.BusinessRuleException.class,
                () -> appointmentService.createAppointmentForPatient(
                        30L, 7L, beforeOpening, null));
        verify(appointmentRepository, never()).saveAndFlush(any(Appointment.class));
    }

    @Test
    void createAppointmentForPatient_offGridMinutes_rejected() {
        Patient patient = new Patient();
        patient.setPatientId(30L);

        User doctor = new User();
        doctor.setUserId(7L);
        doctor.setRole(Role.DOCTOR);

        LocalDateTime insideHoursButOffGrid = LocalDate.now().plusDays(2).atTime(16, 15);

        when(patientRepository.findById(30L)).thenReturn(Optional.of(patient));
        when(userRepository.findById(7L)).thenReturn(Optional.of(doctor));

        assertThrows(com.onecare.backend.exception.BusinessRuleException.class,
                () -> appointmentService.createAppointmentForPatient(
                        30L, 7L, insideHoursButOffGrid, null));
        verify(appointmentRepository, never()).saveAndFlush(any(Appointment.class));
    }

    @Test
    void createAppointmentForPatient_lastSlotOfTheDay_accepted() {
        Patient patient = new Patient();
        patient.setPatientId(30L);

        User doctor = new User();
        doctor.setUserId(7L);
        doctor.setRole(Role.DOCTOR);

        // 20:30-21:00 — the final slot of the default 16:00-21:00 window
        LocalDateTime lastSlot = LocalDate.now().plusDays(2).atTime(20, 30);

        when(patientRepository.findById(30L)).thenReturn(Optional.of(patient));
        when(userRepository.findById(7L)).thenReturn(Optional.of(doctor));
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(doctor));
        when(appointmentRepository.findByDoctor_UserIdAndAppointmentDate(7L, lastSlot.toLocalDate()))
                .thenReturn(List.of());
        when(appointmentRepository.saveAndFlush(any(Appointment.class))).thenAnswer(invocation -> {
            Appointment appointment = invocation.getArgument(0);
            appointment.setAppointmentId(99L);
            return appointment;
        });

        var response = appointmentService.createAppointmentForPatient(30L, 7L, lastSlot, null);

        assertEquals(AppointmentStatus.SCHEDULED.name(), response.status());
        assertEquals(LocalTime.of(20, 30), response.timeSlot());
    }

    @Test
    void createAppointment_outsideClinicGrid_rejected() {
        // staff booking shares the same grid rule (no parallel implementation)
        User currentUser = new User();
        currentUser.setUserId(10L);
        currentUser.setEmail("alice@example.com");
        currentUser.setRole(Role.ADMIN);

        appUserAuthentication(currentUser, "ROLE_ADMIN");

        Patient patient = new Patient();
        patient.setPatientId(30L);
        patient.setEmail("alice@example.com");

        User doctor = new User();
        doctor.setUserId(7L);
        doctor.setRole(Role.DOCTOR);

        LocalDateTime beforeOpening = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0)
                .withSecond(0).withNano(0);

        when(userRepository.findById(10L)).thenReturn(Optional.of(currentUser));
        when(patientRepository.findById(30L)).thenReturn(Optional.of(patient));
        when(userRepository.findById(7L)).thenReturn(Optional.of(doctor));

        assertThrows(com.onecare.backend.exception.BusinessRuleException.class,
                () -> appointmentService.createAppointment(
                        new CreateAppointmentRequest(7L, 30L, beforeOpening, "Checkup")));
        verify(appointmentRepository, never()).saveAndFlush(any(Appointment.class));
    }

    @Test
    void updateAppointment_rescheduleToOffGridTime_rejected() {
        User admin = new User();
        admin.setUserId(5L);
        admin.setUsername("admin.test");
        admin.setRole(Role.ADMIN);

        appUserAuthentication(admin, "ROLE_ADMIN");

        User doctor = new User();
        doctor.setUserId(7L);
        doctor.setRole(Role.DOCTOR);

        Patient patient = new Patient();
        patient.setPatientId(30L);

        Appointment appointment = new Appointment();
        appointment.setAppointmentId(1L);
        appointment.setPatient(patient);
        appointment.setDoctor(doctor);
        appointment.setAppointmentDate(LocalDate.now().plusDays(1));
        appointment.setTimeSlot(LocalTime.of(16, 0));
        appointment.setStatus(AppointmentStatus.SCHEDULED);

        LocalDateTime offGrid = LocalDate.now().plusDays(2).atTime(16, 15);

        when(userRepository.findById(5L)).thenReturn(Optional.of(admin));
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(appointment));
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(doctor));

        assertThrows(com.onecare.backend.exception.BusinessRuleException.class,
                () -> appointmentService.updateAppointment(1L,
                        new UpdateAppointmentRequest(null, null, offGrid, null, null)));
        verify(appointmentRepository, never()).saveAndFlush(any(Appointment.class));
        assertEquals(LocalTime.of(16, 0), appointment.getTimeSlot()); // unchanged
    }

    private void appUserAuthentication(User user, String... authorities) {
        var granted = java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList();
        var principal = new AppUserDetailsService.AppUserDetails(
                user.getUserId(),
                user.getUsername() != null ? user.getUsername() : "alice",
                "password", true, true, true, true, granted);
        var authentication = new UsernamePasswordAuthenticationToken(principal, "password", granted);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
