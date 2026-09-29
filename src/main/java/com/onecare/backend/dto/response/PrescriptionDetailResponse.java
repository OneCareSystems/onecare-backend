package com.onecare.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.onecare.backend.entity.Prescription;
import com.onecare.backend.enums.PrescriptionStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Detail response for GET /api/prescriptions/{id}.
 *
 * clinicalNotes is only populated when the caller holds
 * PRESCRIPTION_READ_CLINICAL_NOTES (Doctor / Pharmacist). For any other
 * caller it is null and, thanks to @JsonInclude(NON_NULL), the key is
 * omitted from the JSON entirely.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PrescriptionDetailResponse(
        Long prescriptionId,
        Long patientId,
        Long doctorId,
        Long appointmentId,
        LocalDate date,
        PrescriptionStatus status,
        List<PrescriptionItemResponse> items,
        String clinicalNotes,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static PrescriptionDetailResponse from(Prescription prescription, boolean includeClinicalNotes) {
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
                includeClinicalNotes ? prescription.getClinicalNotes() : null,
                prescription.getCreatedAt(),
                prescription.getUpdatedAt());
    }
}
