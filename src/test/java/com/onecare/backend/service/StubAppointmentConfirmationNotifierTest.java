package com.onecare.backend.service;

import com.onecare.backend.dto.AppointmentConfirmation;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class StubAppointmentConfirmationNotifierTest {

    @Test
    void sendBookingConfirmation_logsDispatchWithoutFailing() {
        StubAppointmentConfirmationNotifier notifier = new StubAppointmentConfirmationNotifier();

        assertDoesNotThrow(() -> notifier.sendBookingConfirmation(new AppointmentConfirmation(
                42L, "Nimal Perera", "+94771234567", "nimal@example.com",
                LocalDate.of(2026, 10, 20), LocalTime.of(16, 30))));
    }
}
