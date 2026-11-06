package com.onecare.backend.service;

import com.onecare.backend.dto.request.AppointmentStatusRequest;
import com.onecare.backend.dto.request.GenerateInvoiceRequest;
import com.onecare.backend.entity.Appointment;
import com.onecare.backend.entity.Patient;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.AppointmentStatus;
import com.onecare.backend.enums.Role;
import com.onecare.backend.exception.InvalidAppointmentStatusException;
import com.onecare.backend.repository.AppointmentRepository;
import com.onecare.backend.repository.InvoiceRepository;
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
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class QueueServiceImplTest {

    private AppointmentRepository appointmentRepository;
    private PatientRepository patientRepository;
    private UserRepository userRepository;
    private InvoiceService invoiceService;
    private InvoiceRepository invoiceRepository;
    private QueueServiceImpl queueService;

    @BeforeEach
    void setUp() {
        appointmentRepository = mock(AppointmentRepository.class);
        patientRepository = mock(PatientRepository.class);
        userRepository = mock(UserRepository.class);
        invoiceService = mock(InvoiceService.class);
        invoiceRepository = mock(InvoiceRepository.class);
        queueService = new QueueServiceImpl(
                appointmentRepository,
                patientRepository,
                userRepository,
                invoiceService,
                invoiceRepository);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void updateQueueStatus_transitionsScheduledToCompleted_whenDoctorAndGeneratesInvoice() {
        User doctor = new User();
        doctor.setUserId(21L);
        doctor.setEmail("doctor21@example.com");
        doctor.setRole(Role.DOCTOR);
        authenticate(doctor, "ROLE_DOCTOR");
        when(userRepository.findById(21L)).thenReturn(Optional.of(doctor));

        Appointment appointment = appointment("scheduled", AppointmentStatus.SCHEDULED, 1L, 11L, 21L);
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(invoiceRepository.existsByAppointmentAppointmentId(1L)).thenReturn(false);

        var response = queueService.updateQueueStatus(1L, new AppointmentStatusRequest(AppointmentStatus.COMPLETED));

        assertEquals(AppointmentStatus.COMPLETED.name(), response.status());
        verify(invoiceService).generateInvoice(new GenerateInvoiceRequest(1L, null));
    }

    @Test
    void updateQueueStatus_adminCannotMarkAsCompleted() {
        User admin = new User();
        admin.setUserId(5L);
        admin.setEmail("admin@example.com");
        admin.setRole(Role.ADMIN);
        authenticate(admin, "ROLE_ADMIN");
        when(userRepository.findById(5L)).thenReturn(Optional.of(admin));

        Appointment appointment = appointment("scheduled", AppointmentStatus.SCHEDULED, 1L, 11L, 21L);
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(appointment));

        assertThrows(AccessDeniedException.class,
                () -> queueService.updateQueueStatus(1L, new AppointmentStatusRequest(AppointmentStatus.COMPLETED)));
        verifyNoInteractions(invoiceService);
    }

    @Test
    void updateQueueStatus_differentDoctorCannotMarkAsCompleted() {
        User otherDoctor = new User();
        otherDoctor.setUserId(99L);
        otherDoctor.setEmail("other@example.com");
        otherDoctor.setRole(Role.DOCTOR);
        authenticate(otherDoctor, "ROLE_DOCTOR");
        when(userRepository.findById(99L)).thenReturn(Optional.of(otherDoctor));

        Appointment appointment = appointment("scheduled", AppointmentStatus.SCHEDULED, 1L, 11L, 21L);
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(appointment));

        assertThrows(AccessDeniedException.class,
                () -> queueService.updateQueueStatus(1L, new AppointmentStatusRequest(AppointmentStatus.COMPLETED)));
        verifyNoInteractions(invoiceService);
    }

    @Test
    void updateQueueStatus_adminMarksAsArrived_success() {
        Appointment appointment = appointment("scheduled", AppointmentStatus.SCHEDULED, 1L, 11L, 21L);
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = queueService.updateQueueStatus(1L, new AppointmentStatusRequest(AppointmentStatus.ARRIVED));

        assertEquals(AppointmentStatus.ARRIVED.name(), response.status());
    }

    @Test
    void updateQueueStatus_markingArrivedOnNonScheduled_throwsException() {
        Appointment appointment = appointment("completed", AppointmentStatus.COMPLETED, 1L, 11L, 21L);
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(appointment));

        assertThrows(InvalidAppointmentStatusException.class,
                () -> queueService.updateQueueStatus(1L, new AppointmentStatusRequest(AppointmentStatus.ARRIVED)));
    }

    @Test
    void updateQueueStatus_markingNoShowOnArrivedOrCompleted_throwsException() {
        Appointment arrived = appointment("arrived", AppointmentStatus.ARRIVED, 1L, 11L, 21L);
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(arrived));

        assertThrows(InvalidAppointmentStatusException.class,
                () -> queueService.updateQueueStatus(1L, new AppointmentStatusRequest(AppointmentStatus.NO_SHOW)));
    }

    @Test
    void updateQueueStatus_blocksChangingCompletedToCancelled() {
        Appointment appointment = appointment("completed", AppointmentStatus.COMPLETED, 1L, 11L, 21L);
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(appointment));

        assertThrows(InvalidAppointmentStatusException.class,
                () -> queueService.updateQueueStatus(1L, new AppointmentStatusRequest(AppointmentStatus.CANCELLED)));
    }

    @Test
    void updateQueueStatus_blocksChangingNoShowToOtherStatus() {
        Appointment appointment = appointment("no_show", AppointmentStatus.NO_SHOW, 1L, 11L, 21L);
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(appointment));

        assertThrows(InvalidAppointmentStatusException.class,
                () -> queueService.updateQueueStatus(1L, new AppointmentStatusRequest(AppointmentStatus.ARRIVED)));
    }

    @Test
    void getQueueToday_ordersAppointmentsByTimeAndSkipsCancelledAndNoShow() {
        User currentUser = new User();
        currentUser.setUserId(10L);
        currentUser.setEmail("doctor@example.com");
        currentUser.setRole(Role.DOCTOR);
        authenticate(currentUser, "ROLE_DOCTOR");

        Patient patient = new Patient();
        patient.setPatientId(30L);
        patient.setEmail("doctor@example.com");

        User doctor = new User();
        doctor.setUserId(10L);
        doctor.setEmail("doctor@example.com");
        doctor.setRole(Role.DOCTOR);

        Appointment later = appointment("later", AppointmentStatus.SCHEDULED, 2L, 30L, 10L);
        later.setAppointmentDate(LocalDate.now());
        later.setTimeSlot(LocalTime.of(11, 0));
        later.setPatient(patient);
        later.setDoctor(doctor);

        Appointment earlier = appointment("earlier", AppointmentStatus.SCHEDULED, 1L, 30L, 10L);
        earlier.setAppointmentDate(LocalDate.now());
        earlier.setTimeSlot(LocalTime.of(9, 0));
        earlier.setPatient(patient);
        earlier.setDoctor(doctor);

        Appointment cancelled = appointment("cancelled", AppointmentStatus.CANCELLED, 3L, 30L, 10L);
        cancelled.setAppointmentDate(LocalDate.now());
        cancelled.setTimeSlot(LocalTime.of(10, 0));
        cancelled.setPatient(patient);
        cancelled.setDoctor(doctor);

        Appointment noShow = appointment("no_show", AppointmentStatus.NO_SHOW, 4L, 30L, 10L);
        noShow.setAppointmentDate(LocalDate.now());
        noShow.setTimeSlot(LocalTime.of(10, 30));
        noShow.setPatient(patient);
        noShow.setDoctor(doctor);

        when(appointmentRepository.findByAppointmentDateOrderByTimeSlotAscAppointmentIdAsc(LocalDate.now()))
                .thenReturn(List.of(later, earlier, cancelled, noShow));
        when(userRepository.findById(10L)).thenReturn(Optional.of(currentUser));
        when(patientRepository.findAll()).thenReturn(List.of(patient));

        var queue = queueService.getQueueToday();

        assertEquals(2, queue.size());
        assertEquals(1L, queue.get(0).appointmentId());
        assertEquals(2L, queue.get(1).appointmentId());
    }

    private Appointment appointment(String label, AppointmentStatus status, Long appointmentId, Long patientId,
            Long doctorId) {
        Appointment appointment = new Appointment();
        appointment.setAppointmentId(appointmentId);
        appointment.setAppointmentDate(LocalDate.now());
        appointment.setTimeSlot(LocalTime.of(9, 0));
        appointment.setStatus(status);
        appointment.setReason(label);

        Patient patient = new Patient();
        patient.setPatientId(patientId);
        patient.setEmail("doctor@example.com");
        appointment.setPatient(patient);

        User doctor = new User();
        doctor.setUserId(doctorId);
        doctor.setEmail("doctor@example.com");
        doctor.setRole(Role.DOCTOR);
        appointment.setDoctor(doctor);

        return appointment;
    }

    private void authenticate(User user, String authority) {
        var principal = new AppUserDetailsService.AppUserDetails(
                user.getUserId(),
                user.getUsername() != null ? user.getUsername() : "doctor",
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
