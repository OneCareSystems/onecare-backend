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
        scheduler = new AppointmentScheduler(appointmentRepository, "21:15");
    }

    @Test
    void processDailyNoShows_onlyTransitionsScheduledAppointments() {
        LocalDate today = LocalDate.now();

        Appointment app1 = new Appointment();
        app1.setAppointmentId(1L);
        app1.setStatus(AppointmentStatus.SCHEDULED);

        Appointment app2 = new Appointment();
        app2.setAppointmentId(2L);
        app2.setStatus(AppointmentStatus.SCHEDULED);

        when(appointmentRepository.findByAppointmentDateAndStatus(today, AppointmentStatus.SCHEDULED))
                .thenReturn(List.of(app1, app2));

        int updatedCount = scheduler.processDailyNoShows(today);

        assertEquals(2, updatedCount);
        assertEquals(AppointmentStatus.NO_SHOW, app1.getStatus());
        assertEquals(AppointmentStatus.NO_SHOW, app2.getStatus());
        verify(appointmentRepository, times(2)).save(any(Appointment.class));
    }

    @Test
    void processDailyNoShows_doesNotTouchArrivedOrCompletedOrCancelled() {
        LocalDate today = LocalDate.now();

        when(appointmentRepository.findByAppointmentDateAndStatus(today, AppointmentStatus.SCHEDULED))
                .thenReturn(List.of());

        int updatedCount = scheduler.processDailyNoShows(today);

        assertEquals(0, updatedCount);
        verify(appointmentRepository, never()).save(any(Appointment.class));
    }

    @Test
    void getCutoffTime_parsesCorrectly() {
        assertEquals(LocalTime.of(21, 15), scheduler.getCutoffTime());
    }
}
