package com.onecare.backend.dto.request;

/**
 * Request body for POST /api/invoices
 *
 * Exactly one source must be given and it cannot be both:
 * <ul>
 *   <li>{@code appointmentId} - billed consultation: consultation charge +
 *   dispensed prescription items;</li>
 *   <li>{@code dispenseId} - one external dispensing event: medicines only,
 *   no consultation line.</li>
 * </ul>
 * Deliberately has NO status, NO patientId, NO total and NO lines:
 * everything is derived server-side at generation time, so a client cannot
 * influence any of it.
 */
public record GenerateInvoiceRequest(
        Long appointmentId,
        Long dispenseId) {
}
