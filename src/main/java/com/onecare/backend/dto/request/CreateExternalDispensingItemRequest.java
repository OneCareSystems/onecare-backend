package com.onecare.backend.dto.request;

public record CreateExternalDispensingItemRequest(
        Long medicineId,
        Long prescriptionItemId,
        Integer quantity,
        String medicineName) {

    public CreateExternalDispensingItemRequest(Long medicineId, Integer quantity) {
        this(medicineId, null, quantity, null);
    }
}
