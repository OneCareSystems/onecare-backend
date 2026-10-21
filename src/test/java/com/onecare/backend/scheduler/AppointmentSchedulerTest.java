package com.onecare.backend.scheduler;

import com.onecare.backend.entity.Appointment;
import com.onecare.backend.enums.AppointmentStatus;
import com.onecare.backend.repository.AppointmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AppointmentSchedulerTest {

    @Mock
    private AppointmentRepository appointmentRepository;

    private AppointmentScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new AppointmentScheduler(appointmentRepository, 15);
    }

    @Test
    void processDailyNoShows_onlyTransitionsAppointmentsPastTheirGracePeriod() {
        LocalDate date = LocalDate.of(2026, 10, 20);
        LocalDateTime now = date.atTime(22, 15);

        Appointment app1 = new Appointment();
        app1.setAppointmentId(1L);
        app1.setTimeSlot(LocalTime.of(22, 0));
        app1.setStatus(AppointmentStatus.SCHEDULED);

        Appointment app2 = new Appointment();
        app2.setAppointmentId(2L);
        app2.setTimeSlot(LocalTime.of(22, 1));
        app2.setStatus(AppointmentStatus.SCHEDULED);

        when(appointmentRepository.findByAppointmentDateAndStatus(date, AppointmentStatus.SCHEDULED))
                .thenReturn(List.of(app1, app2));

        int updatedCount = scheduler.processDailyNoShows(date, now);

        assertEquals(1, updatedCount);
        assertEquals(AppointmentStatus.NO_SHOW, app1.getStatus());
        assertEquals(AppointmentStatus.SCHEDULED, app2.getStatus());
        verify(appointmentRepository).save(app1);
        verify(appointmentRepository, never()).save(app2);
    }

    @Test
    void processDailyNoShows_doesNotMarkAppointmentBeforeGracePeriodExpires() {
        LocalDate date = LocalDate.of(2026, 10, 20);
        LocalDateTime now = date.atTime(22, 14, 59);
        Appointment appointment = new Appointment();
        appointment.setAppointmentId(3L);
        appointment.setTimeSlot(LocalTime.of(22, 0));
        appointment.setStatus(AppointmentStatus.SCHEDULED);

        when(appointmentRepository.findByAppointmentDateAndStatus(date, AppointmentStatus.SCHEDULED))
                .thenReturn(List.of(appointment));

        int updatedCount = scheduler.processDailyNoShows(date, now);

        assertEquals(0, updatedCount);
        assertEquals(AppointmentStatus.SCHEDULED, appointment.getStatus());
        verify(appointmentRepository, never()).save(any(Appointment.class));
    }

    @Test
    void processDailyNoShows_marksAppointmentAtGracePeriodBoundary() {
        LocalDate date = LocalDate.of(2026, 10, 20);
        Appointment appointment = new Appointment();
        appointment.setAppointmentId(4L);
        appointment.setTimeSlot(LocalTime.of(22, 0));
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        when(appointmentRepository.findByAppointmentDateAndStatus(date, AppointmentStatus.SCHEDULED))
                .thenReturn(List.of(appointment));

        int updatedCount = scheduler.processDailyNoShows(date, date.atTime(22, 15));

        assertEquals(1, updatedCount);
        assertEquals(AppointmentStatus.NO_SHOW, appointment.getStatus());
        verify(appointmentRepository).save(appointment);
    }
}
