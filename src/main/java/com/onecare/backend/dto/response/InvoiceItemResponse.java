package com.onecare.backend.dto.response;

import com.onecare.backend.entity.InvoiceItem;

import java.math.BigDecimal;

/**
 * One billed line. unitPrice and lineTotal are the values locked on the
 * invoice at generation time - never re-read from the medicine catalog.
 */
public record InvoiceItemResponse(
        Long invoiceItemId,
        Long medicineId,
        String description,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal) {

    public static InvoiceItemResponse from(InvoiceItem item) {
        return new InvoiceItemResponse(
                item.getInvoiceItemId(),
                item.getMedicine() != null ? item.getMedicine().getMedicineId() : null,
                item.getDescription(),
                item.getQuantity(),
                item.getUnitPrice(),
                item.getLineTotal());
    }
}
