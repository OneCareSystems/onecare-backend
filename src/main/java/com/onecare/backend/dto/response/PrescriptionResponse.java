package com.onecare.backend.dto.response;

import com.onecare.backend.entity.Prescription;
import com.onecare.backend.enums.PrescriptionStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * List response for GET /api/prescriptions.
 *
 * This record has NO clinicalNotes field at all, so clinical notes
 * can never leak through the list endpoint — not by configuration,
 * not by accident.
 */
public record PrescriptionResponse(
        Long prescriptionId,
        Long patientId,
        Long doctorId,
        Long appointmentId,
        LocalDate date,
        PrescriptionStatus status,
        List<PrescriptionItemResponse> items,
        LocalDateTime createdAt) {

    public static PrescriptionResponse from(Prescription prescription) {
        return new PrescriptionResponse(
                prescription.getPrescriptionId(),
                prescription.getPatient().getPatientId(),
                prescription.getDoctor().getUserId(),
                prescription.getAppointment() != null
                        ? prescription.getAppointment().getAppointmentId()
                        : null,
                prescription.getDate(),
                prescription.getStatus(),
                prescription.getItems().stream()
                        .map(PrescriptionItemResponse::from)
                        .toList(),
                prescription.getCreatedAt());
    }
}
