package com.onecare.backend.dto.response;

import com.onecare.backend.enums.PrescriptionStatus;
import java.math.BigDecimal;
import java.util.List;

public record DispensePreviewResponse(Long prescriptionId, Long patientId,
        PrescriptionStatus status, List<Item> items) {
    public record Item(Long itemId, Long medicineId, int prescribedQuantity,
            int quantityDispensed, int remainingQuantity, int availableStock,
            BigDecimal unitPrice, boolean dispensable, int shortfallQuantity, String reason) { }
}
