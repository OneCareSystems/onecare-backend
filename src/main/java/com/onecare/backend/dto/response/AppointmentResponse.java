package com.onecare.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.onecare.backend.entity.Appointment;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Appointment response.
 *
 * clinicalNotes is only populated for callers holding
 * APPOINTMENT_READ_CLINICAL_NOTES (Doctor / Pharmacist). For every other
 * caller it is null and, thanks to @JsonInclude(NON_NULL), the key is
 * omitted from the JSON entirely.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AppointmentResponse(
        Long appointmentId,
        Long patientId,
        Long doctorId,
        LocalDate appointmentDate,
        LocalTime timeSlot,
        String status,
        String reason,
        String clinicalNotes,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** Maps an appointment without exposing clinical notes. */
    public static AppointmentResponse from(Appointment appointment) {
        return from(appointment, false);
    }

    public static AppointmentResponse from(Appointment appointment, boolean includeClinicalNotes) {
        if (appointment == null) {
            return null;
        }

        return new AppointmentResponse(
                appointment.getAppointmentId(),
                appointment.getPatient() != null ? appointment.getPatient().getPatientId() : null,
                appointment.getDoctor() != null ? appointment.getDoctor().getUserId() : null,
                appointment.getAppointmentDate(),
                appointment.getTimeSlot(),
                appointment.getStatus() != null ? appointment.getStatus().name() : null,
                appointment.getReason(),
                includeClinicalNotes ? appointment.getClinicalNotes() : null,
                appointment.getCreatedAt(),
                appointment.getUpdatedAt());
    }
}
