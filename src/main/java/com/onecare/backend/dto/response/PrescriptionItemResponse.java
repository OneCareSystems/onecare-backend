package com.onecare.backend.dto.response;

import com.onecare.backend.entity.PrescriptionItem;
import com.onecare.backend.enums.PrescriptionItemType;

/**
 * One line of a prescription.
 * medicineName carries the catalog name for IN_HOUSE items and the free
 * text entered by the doctor for EXTERNAL_PURCHASE items, so the frontend
 * can render a single field. medicineId is null for external items.
 */
public record PrescriptionItemResponse(
        Long itemId,
        PrescriptionItemType itemType,
        Long medicineId,
        String medicineName,
        String dosage,
        String frequency,
        Integer durationDays,
        Integer quantity) {

    public static PrescriptionItemResponse from(PrescriptionItem item) {
        return new PrescriptionItemResponse(
                item.getItemId(),
                item.getItemType(),
                item.getMedicine() != null ? item.getMedicine().getMedicineId() : null,
                item.getMedicine() != null ? item.getMedicine().getName() : item.getMedicineName(),
                item.getDosage(),
                item.getFrequency(),
                item.getDurationDays(),
                item.getQuantity());
    }
}
