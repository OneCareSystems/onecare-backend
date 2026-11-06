package com.onecare.backend.dto.response;

import com.onecare.backend.entity.Invoice;
import com.onecare.backend.enums.InvoiceStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Detail view of an invoice (GET /api/invoices/{id}, generation, edit, payment).
 * outstandingBalance is derived: total - amountPaid.
 */
public record InvoiceResponse(
        Long invoiceId,
        String invoiceNumber,
        Long patientId,
        Long appointmentId,
        Long dispenseId,
        InvoiceStatus status,
        BigDecimal total,
        BigDecimal amountPaid,
        BigDecimal outstandingBalance,
        Long createdBy,
        LocalDateTime createdAt,
        List<InvoiceItemResponse> items,
        List<PaymentResponse> payments) {

    public static InvoiceResponse from(Invoice invoice) {
        return new InvoiceResponse(
                invoice.getInvoiceId(),
                invoice.getInvoiceNumber(),
                invoice.getPatient() != null ? invoice.getPatient().getPatientId() : null,
                invoice.getAppointment() != null ? invoice.getAppointment().getAppointmentId() : null,
                invoice.getDispense() != null ? invoice.getDispense().getDispenseId() : null,
                invoice.getStatus(),
                invoice.getTotal(),
                invoice.getAmountPaid(),
                invoice.getOutstandingBalance(),
                invoice.getCreatedBy() != null ? invoice.getCreatedBy().getUserId() : null,
                invoice.getCreatedAt(),
                invoice.getItems().stream()
                        .map(InvoiceItemResponse::from)
                        .toList(),
                invoice.getPayments().stream()
                        .map(PaymentResponse::from)
                        .toList());
    }
}
