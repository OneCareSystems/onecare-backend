package com.onecare.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request body for PUT /api/invoices/{id}.
 *
 * The list is the complete set of medicine lines that must remain on the
 * invoice; anything omitted is removed. The consultation charge line is
 * system-managed and never appears here. No unit price and no status can be
 * sent: both are server-owned.
 */
public record UpdateInvoiceRequest(
        @NotNull @NotEmpty @Valid
        List<UpdateInvoiceLineRequest> items) {
}
