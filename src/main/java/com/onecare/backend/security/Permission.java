package com.onecare.backend.security;

public final class Permission {

    private Permission() {}

    public static final String USER_CREATE = "USER_CREATE";
    public static final String USER_READ_ALL = "USER_READ_ALL";
    public static final String USER_READ_OWN = "USER_READ_OWN";
    public static final String USER_UPDATE = "USER_UPDATE";
    public static final String USER_DELETE = "USER_DELETE";
    public static final String USER_ROLE_ASSIGN = "USER_ROLE_ASSIGN";

    public static final String PATIENT_CREATE = "PATIENT_CREATE";
    public static final String PATIENT_READ_ALL = "PATIENT_READ_ALL";
    public static final String PATIENT_READ = "PATIENT_READ";
    public static final String PATIENT_UPDATE = "PATIENT_UPDATE";
    public static final String PATIENT_DELETE = "PATIENT_DELETE";

    public static final String MEDICINE_CREATE = "MEDICINE_CREATE";
    public static final String MEDICINE_READ_ALL = "MEDICINE_READ_ALL";

    public  static final String MEDICINE_READ = "MEDICINE_READ";
    public static final String MEDICINE_UPDATE = "MEDICINE_UPDATE";
    public static final String MEDICINE_DELETE = "MEDICINE_DELETE";

    public static final String PRESCRIPTION_CREATE = "PRESCRIPTION_CREATE";
    public static final String PRESCRIPTION_READ_ALL = "PRESCRIPTION_READ_ALL";
    public static final String PRESCRIPTION_READ = "PRESCRIPTION_READ";
    public static final String PRESCRIPTION_UPDATE = "PRESCRIPTION_UPDATE";
    public static final String PRESCRIPTION_DISPENSE = "PRESCRIPTION_DISPENSE";

    public static final String APPOINTMENT_CREATE = "APPOINTMENT_CREATE";
    public static final String APPOINTMENT_READ_ALL = "APPOINTMENT_READ_ALL";
    public static final String APPOINTMENT_READ = "APPOINTMENT_READ";
    public static final String APPOINTMENT_READ_OWN = "APPOINTMENT_READ_OWN";
    public static final String APPOINTMENT_UPDATE = "APPOINTMENT_UPDATE";


    public static final String AUDIT_READ = "AUDIT_READ";
    public static final String AUDIT_READ_ALL = "AUDIT_READ_ALL";
    public static final String REPORT_GENERATE = "REPORT_GENERATE";
}