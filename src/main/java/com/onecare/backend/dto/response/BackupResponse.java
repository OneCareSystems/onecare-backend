package com.onecare.backend.dto.response;

import java.time.LocalDateTime;

/** One entry of GET /api/system/backups (AC1: timestamp per entry). */
public record BackupResponse(
        Long backupId,
        LocalDateTime createdAt,
        String filename,
        Long sizeBytes) {
}
