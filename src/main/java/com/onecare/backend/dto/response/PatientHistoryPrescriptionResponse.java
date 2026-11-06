package com.onecare.backend.dto.response;

import com.onecare.backend.entity.Prescription;
import com.onecare.backend.enums.PrescriptionStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Prescription details together with the appointment that produced it. */
public record PatientHistoryPrescriptionResponse(
        Long prescriptionId,
        Long patientId,
        Long doctorId,
        LocalDate date,
        PrescriptionStatus status,
        List<PrescriptionItemResponse> items,
        LocalDateTime createdAt,
        AppointmentResponse appointment) {

    public static PatientHistoryPrescriptionResponse from(
            Prescription prescription, boolean includeClinicalNotes) {
        return new PatientHistoryPrescriptionResponse(
                prescription.getPrescriptionId(),
                prescription.getPatient().getPatientId(),
                prescription.getDoctor().getUserId(),
                prescription.getDate(),
                prescription.getStatus(),
                prescription.getItems().stream()
                        .map(PrescriptionItemResponse::from)
                        .toList(),
                prescription.getCreatedAt(),
                AppointmentResponse.from(prescription.getAppointment(), includeClinicalNotes));
    }
}
