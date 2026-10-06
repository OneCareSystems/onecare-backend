package com.onecare.backend.dto.request;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;

public record UpdateAppointmentRequest(
        @Positive(message = "Doctor ID must be positive") Long doctorId,

        @Positive(message = "Patient ID must be positive") Long patientId,

        @Future(message = "Appointment date/time must be in the future") LocalDateTime appointmentDateTime,

        String reason) {
}
