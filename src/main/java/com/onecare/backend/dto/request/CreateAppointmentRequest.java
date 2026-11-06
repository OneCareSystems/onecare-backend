package com.onecare.backend.dto.request;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;

public record CreateAppointmentRequest(
        @NotNull(message = "Doctor ID is required")
        @Positive(message = "Doctor ID must be positive")
        Long doctorId,

        @NotNull(message = "Patient ID is required")
        @Positive(message = "Patient ID must be positive")
        Long patientId,

        @NotNull(message = "Appointment date/time is required")
        @Future(message = "Appointment date/time must be in the future") 
        LocalDateTime appointmentDateTime,

        String reason) {
}
