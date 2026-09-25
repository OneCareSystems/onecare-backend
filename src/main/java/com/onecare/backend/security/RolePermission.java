package com.onecare.backend.security;
import com.onecare.backend.enums.Role;

import java.util.Set;

public final class RolePermission {

    private RolePermission() {
    }

    public static Set<String> getPermissions(Role role) {

        return switch (role) {

            case SUPER_ADMIN -> Set.of(
                    Permission.USER_CREATE,
                    Permission.USER_READ_ALL,
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

                    Permission.APPOINTMENT_CREATE,
                    Permission.APPOINTMENT_READ_ALL,
                    Permission.APPOINTMENT_READ,
                    Permission.APPOINTMENT_UPDATE,

                    Permission.AUDIT_READ_ALL
            );

            case ADMIN -> Set.of(
                    Permission.USER_CREATE,
                    Permission.USER_READ_ALL,
                    Permission.USER_UPDATE,

                    Permission.PATIENT_CREATE,
                    Permission.PATIENT_READ_ALL,
                    Permission.PATIENT_READ,
                    Permission.PATIENT_UPDATE,

                    Permission.MEDICINE_CREATE,
                    Permission.MEDICINE_READ_ALL,
                    Permission.MEDICINE_READ,
                    Permission.MEDICINE_UPDATE,

                    Permission.PRESCRIPTION_CREATE,
                    Permission.PRESCRIPTION_READ_ALL,
                    Permission.PRESCRIPTION_READ,
                    Permission.PRESCRIPTION_UPDATE,

                    Permission.APPOINTMENT_CREATE,
                    Permission.APPOINTMENT_READ_ALL,
                    Permission.APPOINTMENT_READ,
                    Permission.APPOINTMENT_UPDATE,

                    Permission.AUDIT_READ_ALL
            );

            case DOCTOR -> Set.of(
                    Permission.PATIENT_READ_ALL,
                    Permission.PATIENT_READ,
                    Permission.PATIENT_UPDATE,

                    Permission.APPOINTMENT_READ_ALL,
                    Permission.APPOINTMENT_READ,
                    Permission.APPOINTMENT_UPDATE,

                    Permission.PRESCRIPTION_CREATE,
                    Permission.PRESCRIPTION_READ_ALL,
                    Permission.PRESCRIPTION_READ,
                    Permission.PRESCRIPTION_UPDATE
            );

            case PHARMACIST -> Set.of(
                    Permission.MEDICINE_CREATE,
                    Permission.MEDICINE_READ_ALL,
                    Permission.MEDICINE_READ,
                    Permission.MEDICINE_UPDATE,
                    Permission.MEDICINE_DELETE,

                    Permission.PRESCRIPTION_READ_ALL,
                    Permission.PRESCRIPTION_READ,
                    Permission.PRESCRIPTION_UPDATE
            );
        };
    }
}