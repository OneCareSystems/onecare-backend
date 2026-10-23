package com.onecare.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Updates the day of the month on which the automatic monthly backup runs
 * at end of day (23:59). Days beyond the current month's length run on the
 * last day of that month (e.g. 31 in February runs on the 28th/29th).
 */
public record UpdateBackupScheduleRequest(
        @NotNull(message = "dayOfMonth is required")
        @Min(value = 1, message = "dayOfMonth must be between 1 and 31")
        @Max(value = 31, message = "dayOfMonth must be between 1 and 31")
        Integer dayOfMonth) {
}
