package com.onecare.backend.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Outbound booking confirmation payload Carries everything an SMS/email confirmation template
 * needs once the real notification provider is wired in (DDP-33).
 */
public record AppointmentConfirmation(
        Long appointmentId,
        String patientFullName,
        String patientContactNo,
        String patientEmail,
        LocalDate appointmentDate,
        LocalTime timeSlot) {
}
