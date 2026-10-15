package com.onecare.backend.dto.response;

import java.time.LocalDate;
import java.time.LocalTime;

public record QueueResponse(
        Long appointmentId,
        Long patientId,
        String patientFullName,
        Long doctorId,
        LocalDate appointmentDate,
        LocalTime timeSlot,
        String status,
        Integer queuePosition) {
}
