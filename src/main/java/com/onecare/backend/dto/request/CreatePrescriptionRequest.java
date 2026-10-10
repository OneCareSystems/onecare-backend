package com.onecare.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request body for POST /api/prescriptions.
 *
 * Deliberately has NO status, NO doctorId and NO clinicalNotes fields:
 * - status is always set server-side to ISSUED
 * - doctor is always the authenticated user (must have the DOCTOR role)
 * so a client can never control any of those values.
 * Clinical notes live on the Appointment entity, not on the prescription.
 * appointmentId is required — every prescription belongs to an appointment.
 */
public record CreatePrescriptionRequest(
        @NotNull 
        Long patientId,

        @NotNull
        Long appointmentId,

        @NotNull @NotEmpty @Valid 
        List<PrescriptionItemRequest> items) {
}
