package com.onecare.backend.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * One corrected line inside PUT /api/invoices/{id}.
 *
 * Only the billed quantity of an EXISTING medicine line can change; the unit
 * price stays the snapshot stored on the invoice item, so a line edit can never
 * re-price medicine. Lines omitted from the request are dropped (PUT semantics).
 */
public record UpdateInvoiceLineRequest(
        @NotNull
        Long invoiceItemId,

        @NotNull
        @Min(value = 1, message = "quantity must be at least 1")
        Integer quantity) {
}
