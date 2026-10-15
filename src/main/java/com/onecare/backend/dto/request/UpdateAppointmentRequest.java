package com.onecare.backend.dto.request;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * Request body for PUT /api/appointments/{id}.
 *
 * clinicalNotes is optional: {@code null} leaves the existing notes unchanged.
 * A non-null value is only accepted from callers holding
 * APPOINTMENT_WRITE_CLINICAL_NOTES (Doctor only) — anyone else gets 403.
 */
public record UpdateAppointmentRequest(
        @Positive(message = "Doctor ID must be positive") Long doctorId,

        @Positive(message = "Patient ID must be positive") Long patientId,

        @Future(message = "Appointment date/time must be in the future") LocalDateTime appointmentDateTime,

        String reason,

        @Size(max = 2000) String clinicalNotes) {
}
