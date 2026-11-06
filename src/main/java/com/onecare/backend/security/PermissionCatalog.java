package com.onecare.backend.security;

import java.util.Set;

/**
 * The single list of permission strings the system understands (FR003).
 * Anything not in this catalog cannot be persisted by
 * PUT /api/roles/{role}/permissions (BR006).
 *
 * {@link #PRIVILEGED} is the BR004 set: permissions only the SUPER_ADMIN
 * role may ever hold.
 */
public final class PermissionCatalog {

    private PermissionCatalog() {
    }

    /** Every permission string the system understands (BR006). */
    public static final Set<String> ALL = Set.of(
            Permission.USER_CREATE,
            Permission.USER_READ_ALL,
            Permission.USER_READ_OWN,
            Permission.USER_UPDATE,
            Permission.USER_DELETE,
            Permission.USER_ROLE_ASSIGN,

            Permission.PATIENT_CREATE,
            Permission.PATIENT_READ_ALL,
            Permission.PATIENT_READ,
            Permission.PATIENT_UPDATE,
            Permission.PATIENT_DELETE,

            Permission.MEDICINE_CREATE,
            Permission.MEDICINE_READ_ALL,
            Permission.MEDICINE_READ,
            Permission.MEDICINE_UPDATE,
            Permission.MEDICINE_DELETE,

            Permission.PRESCRIPTION_CREATE,
            Permission.PRESCRIPTION_READ_ALL,
            Permission.PRESCRIPTION_READ,
            Permission.PRESCRIPTION_UPDATE,
            Permission.PRESCRIPTION_DISPENSE,
            Permission.PRESCRIPTION_STATUS_UPDATE,

            Permission.APPOINTMENT_CREATE,
            Permission.APPOINTMENT_READ_ALL,
            Permission.APPOINTMENT_READ,
            Permission.APPOINTMENT_UPDATE,
            Permission.APPOINTMENT_READ_CLINICAL_NOTES,
            Permission.APPOINTMENT_WRITE_CLINICAL_NOTES,

            Permission.INVOICE_CREATE,
            Permission.INVOICE_READ,
            Permission.INVOICE_UPDATE,
            Permission.INVOICE_PAY,

            Permission.AUDIT_READ,
            Permission.AUDIT_READ_ALL,
            Permission.REPORT_GENERATE,
            Permission.REPORT_CLINIC_GENERATE,
            Permission.REPORT_PHARMACY_GENERATE,

            Permission.SCHEDULE_READ,
            Permission.SCHEDULE_MANAGE,

            Permission.ROLE_PERMISSION_READ,
            Permission.ROLE_PERMISSION_UPDATE,

            Permission.SYSTEM_BACKUP,
            Permission.SYSTEM_RESTORE);

    /**
     * BR004: role administration, permission management and backup/restore
     * may only ever be held by the SUPER_ADMIN role. (Deliberately excludes
     * AUDIT_READ_ALL: ADMIN has held it since DDP-26 and it stays grantable.)
     */
    public static final Set<String> PRIVILEGED = Set.of(
            Permission.USER_DELETE,
            Permission.USER_ROLE_ASSIGN,
            Permission.ROLE_PERMISSION_READ,
            Permission.ROLE_PERMISSION_UPDATE,
            Permission.SYSTEM_BACKUP,
            Permission.SYSTEM_RESTORE);
}
