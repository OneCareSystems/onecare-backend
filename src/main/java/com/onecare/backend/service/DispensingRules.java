package com.onecare.backend.service;

import com.onecare.backend.entity.Medicine;
import java.time.LocalDate;

/** Shared, side-effect-free dispensing rules. */
public final class DispensingRules {
    private DispensingRules() { }

    public static int quantityToDispense(int requested, int available) {
        return Math.min(Math.max(0, requested), Math.max(0, available));
    }

    public static String unavailableReason(Medicine medicine) {
        if (medicine == null) return "Medicine is required for in-clinic dispensing";
        if (Boolean.TRUE.equals(medicine.getIsQuarantined())) {
            return "Medicine is quarantined with id: " + medicine.getMedicineId();
        }
        if (medicine.getExpiryDate() != null && medicine.getExpiryDate().isBefore(LocalDate.now())) {
            return "Medicine is expired with id: " + medicine.getMedicineId();
        }
        return null;
    }
}
