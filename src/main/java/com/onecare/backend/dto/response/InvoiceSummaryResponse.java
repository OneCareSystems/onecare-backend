package com.onecare.backend.dto.response;

import com.onecare.backend.entity.Invoice;
import com.onecare.backend.enums.InvoiceStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * List row for GET /api/invoices - no line items, no payments, so a page of
 * invoices never triggers N+1 reads. Fetch a single invoice for the full view.
 */
public record InvoiceSummaryResponse(
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
        LocalDateTime createdAt) {

    public static InvoiceSummaryResponse from(Invoice invoice) {
        return new InvoiceSummaryResponse(
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
                invoice.getCreatedAt());
    }
}
