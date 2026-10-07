package com.onecare.backend.service;

import com.onecare.backend.dto.request.GenerateInvoiceRequest;
import com.onecare.backend.dto.request.RecordPaymentRequest;
import com.onecare.backend.dto.request.UpdateInvoiceRequest;
import com.onecare.backend.dto.response.InvoiceResponse;
import com.onecare.backend.dto.response.InvoiceSummaryResponse;
import org.springframework.data.domain.Page;

import java.time.LocalDate;

/**
 * Billing / invoice domain actions (DDP-23, FR035-FR038).
 *
 * Every mutating call runs in one transaction and emits exactly one audit
 * record through the logger (DDP-25 will replace it with the persisted audit
 * event) - the domain never writes a repository save of its own outside this
 * service.
 */
public interface InvoiceService {

    /**
     * Generates an invoice from exactly one source (FR035, UC22): a completed
     * consultation or one external dispensing event. A consultation invoice
     * carries the charge plus the dispensed prescription items; while the
     * prescription has not been collected yet (or never will be) it is
     * consultation-only and the medicine is billed later on per-event
     * dispense invoices. Lines are snapshotted at generation time; a second
     * invoice for the same consultation or dispense event is a 409 (AC7).
     */
    InvoiceResponse generateInvoice(GenerateInvoiceRequest request);

    /**
     * Paginated list (FR038, UC23): default 20 / max 100 rows, newest first,
     * optionally filtered by patient, invoice number, status and date range.
     */
    Page<InvoiceSummaryResponse> listInvoices(Long patientId, String invoiceNumber, String status,
                                              LocalDate dateFrom, LocalDate dateTo,
                                              Integer page, Integer size);

    /** View / reprint a historical invoice (FR038, UC23). */
    InvoiceResponse findInvoiceById(Long id);

    /**
     * Line correction (UC22 alt flow): only unpaid invoices can be edited and
     * the recalculated total may never drop below the amount already paid.
     */
    InvoiceResponse updateInvoiceLines(Long id, UpdateInvoiceRequest request);

    /**
     * Records a cash payment (FR036, FR037, UC23): amount must be positive,
     * at most 2 decimals and never greater than the outstanding balance.
     * Full payment flips the status to PAID, partial payment keeps it UNPAID.
     */
    InvoiceResponse recordPayment(Long id, RecordPaymentRequest request);
}
