package com.onecare.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for PUT /api/prescriptions/{id} — the prescription content/items flow.
 *
 * Only the prescribable content is updatable:
 * - clinicalNotes: optional; {@code null} leaves the existing notes unchanged
 * - items: required; the existing item lines are replaced as a whole
 *
 * Status, doctor, patient, appointment and date are never changed here —
 * those come from the persisted prescription.
 */
public record UpdatePrescriptionRequest(
        @Size(max = 2000)
        String clinicalNotes,

        @NotNull @NotEmpty @Valid
        List<PrescriptionItemRequest> items) {
}
