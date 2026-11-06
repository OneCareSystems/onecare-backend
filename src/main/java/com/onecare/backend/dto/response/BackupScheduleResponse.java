package com.onecare.backend.dto.response;

import java.time.LocalDate;

/**
 * Current automatic monthly backup schedule. {@code nextRunDate} already
 * accounts for short months (e.g. day 31 configured, next run on the last
 * day of the month when it has fewer days).
 */
public record BackupScheduleResponse(
        Integer dayOfMonth,
        String executionTime,
        LocalDate nextRunDate) {
}
