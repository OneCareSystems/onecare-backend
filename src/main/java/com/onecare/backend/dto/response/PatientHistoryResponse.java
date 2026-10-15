package com.onecare.backend.dto.response;

import java.util.List;

/**
 * Response for GET /api/patients/{patientId}/history.
 *
 * Gives a doctor the patient's previous clinical context in one call:
 * - previous appointments, each carrying its clinical notes
 * - previous prescriptions (clinical notes are NOT part of a prescription)
 *
 * "Previous" means anything before today plus any encounter that is already
 * COMPLETED / CANCELLED (appointments) or DISPENSED / CANCELLED (prescriptions).
 */
public record PatientHistoryResponse(
        PatientResponse patient,
        List<AppointmentResponse> appointments,
        List<PatientHistoryPrescriptionResponse> prescriptions) {
}
