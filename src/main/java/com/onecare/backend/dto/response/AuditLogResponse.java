package com.onecare.backend.dto.response;

import com.onecare.backend.entity.AuditLog;

import java.time.LocalDateTime;

/**
 * Read-only view of one audit trail entry (FR041, UC26). The trail is
 * append-only, so this DTO is never used for writes.
 */
public record AuditLogResponse(
        Long logId,
        Long userId,
        String username,
        String actionType,
        String entityType,
        String entityId,
        String description,
        String ipAddress,
        LocalDateTime timestamp) {

    public static AuditLogResponse from(AuditLog auditLog) {
        return new AuditLogResponse(
                auditLog.getLogId(),
                auditLog.getUser() != null ? auditLog.getUser().getUserId() : null,
                auditLog.getUser() != null ? auditLog.getUser().getUsername() : null,
                auditLog.getActionType(),
                auditLog.getEntityType(),
                auditLog.getEntityId(),
                auditLog.getDescription(),
                auditLog.getIpAddress(),
                auditLog.getTimestamp());
    }
}
