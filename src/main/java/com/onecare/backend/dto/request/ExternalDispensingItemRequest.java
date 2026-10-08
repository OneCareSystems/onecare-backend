package com.onecare.backend.dto.request;

public record ExternalDispensingItemRequest(
        Long medicineId,
        Long prescriptionItemId,
        Integer quantity,
        String medicineName
) {

    public ExternalDispensingItemRequest(Long medicineId, Integer quantity) {
        this(medicineId, null, quantity, null);
    }

    public ExternalDispensingItemRequest(Long medicineId, Long prescriptionItemId, Integer quantity) {
        this(medicineId, prescriptionItemId, quantity, null);
    }

    public ExternalDispensingItemRequest(Integer quantity, Long medicineId) {
        this(medicineId, null, quantity, null);
    }
}
