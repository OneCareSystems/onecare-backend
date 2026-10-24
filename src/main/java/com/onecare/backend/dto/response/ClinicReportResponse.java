package com.onecare.backend.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Aggregated clinic report (FR039, UC24). Aggregates only - deliberately no
 * clinical fields such as reasons or clinical notes (BR006).
 *
 * <p>{@code consultationCount} counts completed consultations; the status
 * breakdown doubles as the queue statistics sourced from the same
 * appointment rows the queue view is derived from (DDP-20/22).</p>
 */
public record ClinicReportResponse(
        LocalDate from,
        LocalDate to,
        Long doctorId,
        long totalAppointments,
        long consultationCount,
        long scheduled,
        long arrived,
        long completed,
        long cancelled,
        long noShow,
        long uniquePatients,
        LocalDateTime generatedAt) {
}
