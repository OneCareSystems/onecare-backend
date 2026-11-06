package com.onecare.backend.dto.response;

import com.onecare.backend.entity.Payment;
import com.onecare.backend.enums.PaymentMethod;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One recorded payment - the historical proof behind amountPaid.
 */
public record PaymentResponse(
        Long paymentId,
        BigDecimal amount,
        PaymentMethod paymentMethod,
        Long recordedBy,
        LocalDateTime paidAt) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getPaymentId(),
                payment.getAmount(),
                payment.getPaymentMethod(),
                payment.getRecordedBy() != null ? payment.getRecordedBy().getUserId() : null,
                payment.getPaidAt());
    }
}
