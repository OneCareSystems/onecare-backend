package com.onecare.backend.controller;

import com.onecare.backend.dto.ApiResponse;
import com.onecare.backend.dto.request.GenerateInvoiceRequest;
import com.onecare.backend.dto.request.RecordPaymentRequest;
import com.onecare.backend.dto.request.UpdateInvoiceRequest;
import com.onecare.backend.dto.response.InvoiceResponse;
import com.onecare.backend.dto.response.InvoiceSummaryResponse;
import com.onecare.backend.security.Permission;
import com.onecare.backend.service.InvoiceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@Tag(name = "Invoices", description = "Billing: invoice generation, line correction and "
        + "cash payments. Admin / Super Admin / Pharmacist only.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @Operation(
            summary = "Generate an invoice (FR035, UC22)",
            description = "Exactly one source: appointmentId bills a COMPLETED consultation, "
                    + "dispenseId bills one external dispensing event. A consultation invoice "
                    + "carries a system-managed consultation charge (configured fee) plus one "
                    + "line per dispensed in-house item, quantity and unit price snapshotted "
                    + "from the catalog; while the prescription has not been collected yet it "
                    + "is consultation-only and the medicine is billed later on per-event "
                    + "dispense invoices (medicines only, no consultation line, patient "
                    + "optional for unknown walk-ins). External-purchase items are excluded. "
                    + "Totals use 2-decimal HALF_UP precision, a unique invoice number and "
                    + "timestamp are assigned and the status starts as UNPAID (clients can "
                    + "never send a status). Returns 201; 400 for an incomplete consultation "
                    + "or both/neither source given; 404 for an unknown appointment or "
                    + "dispense event; 409 when that consultation or dispense event is "
                    + "already invoiced (AC7); 403 without INVOICE_CREATE.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Invoice generated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Consultation not completed or validation error"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing INVOICE_CREATE permission"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Appointment or dispense event not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Consultation or dispense event already invoiced")
    })
    @PostMapping
    @PreAuthorize("hasAuthority('" + Permission.INVOICE_CREATE + "')")
    public ResponseEntity<ApiResponse<?>> generateInvoice(
            @Valid @RequestBody GenerateInvoiceRequest request) {

        InvoiceResponse response = invoiceService.generateInvoice(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "Invoice generated successfully", response));
    }

    @Operation(
            summary = "List invoices (FR038, UC23)",
            description = "Paginated, newest first: page defaults to 0, size defaults to 20 and is "
                    + "capped at 100. Optional filters: patientId, partial invoiceNumber, status "
                    + "(UNPAID or PAID - any other value returns 400) and an inclusive date range "
                    + "(dateFrom/dateTo as yyyy-MM-dd, evaluated on createdAt). Rows carry no line "
                    + "items or payments; fetch a single invoice for the full view. "
                    + "Returns 403 without INVOICE_READ.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Invoices retrieved"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid status filter or date range"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing INVOICE_READ permission")
    })
    @GetMapping
    @PreAuthorize("hasAuthority('" + Permission.INVOICE_READ + "')")
    public ResponseEntity<ApiResponse<Page<?>>> listInvoices(
            @Parameter(description = "Filter by patient id")
            @RequestParam(required = false) Long patientId,
            @Parameter(description = "Partial invoice number, case-insensitive")
            @RequestParam(required = false) String invoiceNumber,
            @Parameter(description = "Status filter: UNPAID or PAID")
            @RequestParam(required = false) String status,
            @Parameter(description = "Inclusive lower bound of createdAt (yyyy-MM-dd)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @Parameter(description = "Inclusive upper bound of createdAt (yyyy-MM-dd)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @Parameter(description = "Zero-based page index, default 0")
            @RequestParam(required = false, defaultValue = "0") Integer page,
            @Parameter(description = "Page size, default 20, max 100")
            @RequestParam(required = false, defaultValue = "20") Integer size) {

        Page<InvoiceSummaryResponse> invoices =
                invoiceService.listInvoices(patientId, invoiceNumber, status, dateFrom, dateTo, page, size);

        return ResponseEntity.ok(
                new ApiResponse<>(true, "Invoices retrieved successfully", invoices));
    }

    @Operation(
            summary = "View / reprint an invoice (FR038, UC23)",
            description = "Returns the historical invoice with its snapshotted lines and recorded "
                    + "payments. Line unit prices are the values locked at generation time, never "
                    + "re-read from the catalog. Returns 404 for an unknown invoice and 403 without "
                    + "INVOICE_READ.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Invoice retrieved"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing INVOICE_READ permission"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Invoice not found")
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permission.INVOICE_READ + "')")
    public ResponseEntity<ApiResponse<?>> findInvoiceById(@PathVariable Long id) {

        InvoiceResponse response = invoiceService.findInvoiceById(id);
        return ResponseEntity.ok(
                new ApiResponse<>(true, "Invoice retrieved successfully", response));
    }

    @Operation(
            summary = "Correct invoice lines (UC22 alt flow)",
            description = "Replaces the medicine lines of an UNPAID invoice: the body lists the "
                    + "lines that must remain with their new quantity (>= 1) and omitted lines are "
                    + "dropped; the consultation line is system-managed and always kept. Unit "
                    + "prices stay the snapshots already on the invoice - they cannot be edited. "
                    + "The total is recalculated and the request is rejected if the new total would "
                    + "be less than amountPaid. Paid invoices are immutable. Returns 200; 400 for a "
                    + "paid invoice, a foreign/unknown line or a total below amountPaid; 404 for an "
                    + "unknown invoice; 403 without INVOICE_UPDATE. A rejected request changes "
                    + "nothing (AC3).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Invoice lines corrected"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Paid invoice, line not editable or total below amountPaid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing INVOICE_UPDATE permission"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Invoice not found")
    })
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permission.INVOICE_UPDATE + "')")
    public ResponseEntity<ApiResponse<?>> updateInvoiceLines(
            @PathVariable Long id,
            @Valid @RequestBody UpdateInvoiceRequest request) {

        InvoiceResponse response = invoiceService.updateInvoiceLines(id, request);
        return ResponseEntity.ok(
                new ApiResponse<>(true, "Invoice lines updated successfully", response));
    }

    @Operation(
            summary = "Record a cash payment (FR036, FR037, UC23)",
            description = "Domain action POST /api/invoices/{id}/payments. Cash only: paymentMethod "
                    + "must be CASH, any other value is a 400 and no state changes. amount must be "
                    + "> 0 with at most 2 decimal places and can never exceed the outstanding "
                    + "balance (total - amountPaid). A partial payment keeps the status UNPAID with "
                    + "the raised amountPaid; a payment that settles the balance sets amountPaid = "
                    + "total and the status to PAID. Optimistic locking makes concurrent conflicting "
                    + "payments safe: one succeeds, the other gets 409. Returns 201; 400 for an "
                    + "invalid amount or an already paid invoice; 404 for an unknown invoice; 409 "
                    + "for a concurrent modification; 403 without INVOICE_PAY.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Payment recorded"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Non-cash method, invalid amount or invoice already paid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing INVOICE_PAY permission"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Invoice not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Invoice modified by a concurrent request")
    })
    @PostMapping("/{id}/payments")
    @PreAuthorize("hasAuthority('" + Permission.INVOICE_PAY + "')")
    public ResponseEntity<ApiResponse<?>> recordPayment(
            @PathVariable Long id,
            @Valid @RequestBody RecordPaymentRequest request) {

        InvoiceResponse response = invoiceService.recordPayment(id, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "Payment recorded successfully", response));
    }
}
