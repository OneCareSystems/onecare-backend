package com.onecare.backend.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MedicineRequest(

        @NotBlank(message = "Medicine name is required")
        String name,

        String genericName,

        @NotBlank(message = "Category is required")
        String category,

        @NotBlank(message = "Unit is required")
        String unit,

        @NotNull(message = "Price is required")
        @DecimalMin(
                value = "0.0",
                inclusive = true,
                message = "Price must be greater than or equal to 0"
        )
        BigDecimal price,

        @NotNull(message = "Unit price is required")
        @DecimalMin(
                value = "0.0",
                inclusive = true,
                message = "Unit price must be greater than or equal to 0"
        )
        BigDecimal unitPrice,

        @NotNull(message = "Expiry date is required")
        LocalDate expiryDate,

        @NotNull(message = "Reorder level is required")
        @DecimalMin(
                value = "0.0",
                inclusive = true,
                message = "Reorder level must be greater than or equal to 0"
        )
        Integer reorderLevel,

        Boolean isQuarantined
) {
}