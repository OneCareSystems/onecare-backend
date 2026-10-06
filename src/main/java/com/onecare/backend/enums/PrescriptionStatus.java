package com.onecare.backend.enums;

/**
 * Lifecycle of a prescription (DDP-22).
 * ISSUED -> CANCELLED (doctor cancels while still issued)
 * ISSUED -> DISPENSED (pharmacist dispenses, DDP-25)
 * DISPENSED prescriptions can no longer be cancelled.
 */
public enum PrescriptionStatus {
    ISSUED,
    DISPENSED,
    CANCELLED;
}
