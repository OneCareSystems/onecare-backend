package com.onecare.backend.service;

import com.onecare.backend.dto.request.CreateAppointmentRequest;
import com.onecare.backend.dto.request.UpdateAppointmentRequest;
import com.onecare.backend.entity.Appointment;
import com.onecare.backend.entity.Patient;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.AppointmentStatus;
import com.onecare.backend.enums.Role;
import com.onecare.backend.exception.InvalidAppointmentStatusException;
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
        appointmentService = new AppointmentServiceImpl(appointmentRepository, patientRepository, userRepository);
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
                "Follow-up"));

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
                "Rebook for a different patient");

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

    private void appUserAuthentication(User user, String authority) {
        var principal = new AppUserDetailsService.AppUserDetails(
                user.getUserId(),
                user.getUsername() != null ? user.getUsername() : "alice",
                "password",
                true,
                true,
                true,
                true,
                List.of(new SimpleGrantedAuthority(authority)));

        var authentication = new UsernamePasswordAuthenticationToken(
                principal,
                "password",
                List.of(new SimpleGrantedAuthority(authority)));

        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
