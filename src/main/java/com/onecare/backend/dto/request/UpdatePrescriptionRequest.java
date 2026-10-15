package com.onecare.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request body for PUT /api/prescriptions/{id} — the prescription content/items flow.
 *
 * Only the prescribable content is updatable:
 * - items: required; the existing item lines are replaced as a whole
 *
 * Clinical notes are NOT part of a prescription — they are stored on the
 * Appointment entity. Status, doctor, patient, appointment and date are
 * never changed here — those come from the persisted prescription.
 */
public record UpdatePrescriptionRequest(
        @NotNull @NotEmpty @Valid
        List<PrescriptionItemRequest> items) {
}
