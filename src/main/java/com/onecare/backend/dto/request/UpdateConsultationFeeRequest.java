package com.onecare.backend.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Updates the standard consultation fee applied to the system-managed
 * consultation line of newly generated invoices.
 */
public record UpdateConsultationFeeRequest(
        @NotNull(message = "consultationFee is required")
        @DecimalMin(value = "0.00", message = "consultationFee must be zero or positive")
        BigDecimal consultationFee) {
}
