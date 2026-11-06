package com.onecare.backend.dto.response;

import com.onecare.backend.entity.Prescription;
import com.onecare.backend.enums.PrescriptionStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Detail response for GET /api/prescriptions/{id}.
 *
 * This record has NO clinicalNotes field at all — clinical notes are stored
 * on the Appointment entity and are never returned by prescription APIs.
 */
public record PrescriptionDetailResponse(
        Long prescriptionId,
        Long patientId,
        Long doctorId,
        Long appointmentId,
        LocalDate date,
        PrescriptionStatus status,
        List<PrescriptionItemResponse> items,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static PrescriptionDetailResponse from(Prescription prescription) {
        return new PrescriptionDetailResponse(
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
                prescription.getCreatedAt(),
                prescription.getUpdatedAt());
    }
}
