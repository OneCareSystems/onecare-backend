package com.onecare.backend.service;

import com.onecare.backend.entity.AuditLog;
import com.onecare.backend.entity.User;

/**
 * The single write path for the append-only audit trail (FR041, UC26).
 * Every module - directly or through {@code @Audited}/{@code AuditAspect} -
 * must record audit entries through this service; no component may call
 * {@code auditLogRepository.save(...)} on its own.
 */
public interface AuditService {

    /**
     * Persists one audit record in its own transaction so the entry survives
     * a rollback of the business transaction (e.g. failed logins).
     *
     * @param user        actor, {@code null} when the username is unknown (e.g. failed login)
     * @param actionType  action performed, e.g. {@code PATIENT_CREATE}
     * @param module      owning module, stored as {@code entityType} and used by the
     *                    {@code module} filter on GET /api/audit-logs
     * @param entityId    identifier of the affected entity, may be {@code null}
     * @param description human readable detail; sanitized before persistence
     * @param ipAddress   client IP, may be {@code null}
     * @return the saved record, or {@code null} when persistence failed - an audit
     *         failure is logged but never breaks the business operation
     */
    AuditLog record(User user, String actionType, String module, String entityId,
                    String description, String ipAddress);
}
