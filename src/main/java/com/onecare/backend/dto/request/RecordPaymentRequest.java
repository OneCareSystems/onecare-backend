package com.onecare.backend.dto.request;

import com.onecare.backend.enums.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Request body for POST /api/invoices/{id}/payments.
 *
 * Cash only (BROII): paymentMethod must be CASH - any other value fails
 * deserialization with 400. amount must be positive and carry at most
 * 2 decimal places; that it never exceeds the outstanding balance is checked
 * by the domain.
 *
 * There is no status field: the invoice status is derived from the payments.
 */
public record RecordPaymentRequest(
        @NotNull
        @DecimalMin(value = "0.00", inclusive = false,
                message = "amount must be greater than zero")
        @Digits(integer = 8, fraction = 2,
                message = "amount must have at most 2 decimal places")
        BigDecimal amount,

        @NotNull
        PaymentMethod paymentMethod) {
}
