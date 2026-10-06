package com.onecare.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for POST /api/prescriptions.
 *
 * Deliberately has NO status and NO doctorId fields:
 * - status is always set server-side to ISSUED
 * - doctor is always the authenticated user (must have the DOCTOR role)
 * so a client can never control either value.
 * appointmentId is required — every prescription belongs to an appointment.
 */
public record CreatePrescriptionRequest(
        @NotNull 
        Long patientId,

        @NotNull
        Long appointmentId,
        
        @Size(max = 2000) 
        String clinicalNotes,

        @NotNull @NotEmpty @Valid 
        List<PrescriptionItemRequest> items) {
}
