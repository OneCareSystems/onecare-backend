package com.onecare.backend.enums;

/**
 * Where an item's medicine comes from.
 * IN_HOUSE: stocked by the pharmacy — referenced by medicineId and
 *           validated (exists, not quarantined, not expired), dispensed
 *           and invoiced like before.
 * EXTERNAL_PURCHASE: not in the pharmacy catalog — free-text medicineName,
 *           no stock/dispense/invoice handling.
 */
public enum PrescriptionItemType {
    IN_HOUSE,
    
    EXTERNAL_PURCHASE;
}
