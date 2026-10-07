package com.onecare.backend.enums;

/**
 * Lifecycle of an invoice
 *
 * The status is DERIVED, never accepted from a client:
 * an invoice is always created UNPAID and only becomes PAID when the sum of
 * the recorded payments reaches the invoice total (amountPaid == total).
 * The outstanding balance (total - amount_paid) is what keeps an invoice UNPAID,
 * including after a partial payment.
 */
public enum InvoiceStatus {
    UNPAID,
    PAID
}
