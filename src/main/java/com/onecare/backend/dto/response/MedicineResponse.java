package com.onecare.backend.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record MedicineResponse(

        Long medicineId,

        String name,

        String genericName,

        String category,

        String unit,

        BigDecimal price,

        Integer stockQuantity,

        LocalDate expiryDate,

        Integer reorderLevel,

        BigDecimal unitPrice,

        Boolean isQuarantined,

        LocalDateTime createdAt,

        LocalDateTime updatedAt
) {
}