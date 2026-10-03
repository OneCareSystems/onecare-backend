package com.onecare.backend.dto.response;

import com.onecare.backend.entity.Appointment;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record AppointmentResponse(
        Long appointmentId,
        Long patientId,
        Long doctorId,
        LocalDate appointmentDate,
        LocalTime timeSlot,
        String status,
        String reason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
    public static AppointmentResponse from(Appointment appointment) {
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
                appointment.getCreatedAt(),
                appointment.getUpdatedAt());
    }
}
