package com.onecare.backend.service;

import com.onecare.backend.dto.AppointmentConfirmation;
import com.onecare.backend.dto.request.PublicAppointmentRequest;
import com.onecare.backend.dto.response.AppointmentResponse;
import com.onecare.backend.entity.Patient;
import com.onecare.backend.enums.AppointmentStatus;
import com.onecare.backend.exception.SlotConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DDP-33 unit tests for the public self-service booking flow — AC1 (happy
 * path: SCHEDULED appointment + confirmation triggered) and confirmation
 * failure tolerance.
 */
@ExtendWith(MockitoExtension.class)
class PublicAppointmentServiceImplTest {

    @Mock
    private PatientService patientService;

    @Mock
    private AppointmentService appointmentService;

    @Mock
    private AppointmentConfirmationNotifier confirmationNotifier;

    @InjectMocks
    private PublicAppointmentServiceImpl publicAppointmentService;

    private final LocalDate dateOfBirth = LocalDate.of(1990, 5, 12);
    private final LocalDateTime requestedSlot = LocalDate.now().plusDays(2)
            .atTime(16, 30, 0, 0);

    private PublicAppointmentRequest request() {
        return new PublicAppointmentRequest(
                "Nimal Perera", "0771234567", dateOfBirth, "Male",7L, requestedSlot );
    }

    private Patient patient(long id, String contactNo) {
        Patient patient = new Patient();
        patient.setPatientId(id);
        patient.setFullName("Nimal Perera");
        patient.setContactNo(contactNo);
        patient.setDateOfBirth(dateOfBirth);
        patient.setIsActive(true);
        return patient;
    }

    private AppointmentResponse bookedAppointment(long appointmentId, long patientId) {
        return new AppointmentResponse(
                appointmentId, patientId, "Nimal Perera", 7L,
                requestedSlot.toLocalDate(), requestedSlot.toLocalTime(),
                AppointmentStatus.SCHEDULED.name(), null, null,
                requestedSlot, requestedSlot);
    }

    @Test
    void bookAppointment_createsScheduledAppointment_andTriggersConfirmation() {
        when(patientService.findOrCreateForPublicBooking("Nimal Perera", "0771234567", dateOfBirth ,"Male"))
                .thenReturn(patient(30L, "+94771234567"));
        when(appointmentService.createAppointmentForPatient(30L, 7L, requestedSlot, null))
                .thenReturn(bookedAppointment(99L, 30L));

        AppointmentResponse response = publicAppointmentService.bookAppointment(request());

        assertEquals(99L, response.appointmentId());
        assertEquals(AppointmentStatus.SCHEDULED.name(), response.status());

        ArgumentCaptor<AppointmentConfirmation> captor =
                ArgumentCaptor.forClass(AppointmentConfirmation.class);
        verify(confirmationNotifier).sendBookingConfirmation(captor.capture());
        AppointmentConfirmation confirmation = captor.getValue();
        assertEquals(99L, confirmation.appointmentId());
        assertEquals("Nimal Perera", confirmation.patientFullName());
        assertEquals("+94771234567", confirmation.patientContactNo());
        assertEquals(requestedSlot.toLocalDate(), confirmation.appointmentDate());
        assertEquals(requestedSlot.toLocalTime(), confirmation.timeSlot());
    }

    @Test
    void bookAppointment_confirmationFailure_doesNotFailTheBooking() {
        when(patientService.findOrCreateForPublicBooking("Nimal Perera", "0771234567", dateOfBirth,"Male"))
                .thenReturn(patient(30L, "+94771234567"));
        when(appointmentService.createAppointmentForPatient(30L, 7L, requestedSlot, null))
                .thenReturn(bookedAppointment(99L, 30L));
        doThrow(new IllegalStateException("SMS provider down"))
                .when(confirmationNotifier).sendBookingConfirmation(any());

        AppointmentResponse response = publicAppointmentService.bookAppointment(request());

        assertEquals(99L, response.appointmentId());
    }

    @Test
    void bookAppointment_slotTaken_propagatesConflictWithoutConfirmation() {
        when(patientService.findOrCreateForPublicBooking("Nimal Perera", "0771234567", dateOfBirth,"Male"))
                .thenReturn(patient(30L, "+94771234567"));
        when(appointmentService.createAppointmentForPatient(30L, 7L, requestedSlot, null))
                .thenThrow(new SlotConflictException("Slot taken", requestedSlot.toString(),
                        List.of("2026-10-20T16:00:00")));

        assertThrows(SlotConflictException.class,
                () -> publicAppointmentService.bookAppointment(request()));

        verify(confirmationNotifier, never()).sendBookingConfirmation(any());
    }
}
