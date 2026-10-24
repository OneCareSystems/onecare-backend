package com.onecare.backend.service;

import com.onecare.backend.dto.response.ClinicReportResponse;
import com.onecare.backend.dto.response.PharmacyReportResponse;
import com.onecare.backend.enums.ReportFormat;

import java.time.LocalDate;

/**
 * Read-only report generation over DDP-20/21/22/25/30 data (FR039/FR040,
 * UC24/UC25). No write paths and no duplicated business logic: every report is
 * an aggregation over the entities the operational modules own, and an export
 * renders the very same query result the on-screen report shows (AC5).
 */
public interface ReportService {

    /** A downloadable export: filename, media type and raw file bytes. */
    record ReportFile(String filename, String contentType, byte[] content) {
    }

    /** Clinic report (FR039, UC24): patient, consultation and queue aggregates. */
    ClinicReportResponse getClinicReport(LocalDate from, LocalDate to, Long doctorId);

    /** Clinic report rendered as CSV/PDF; audited as a report export (api-standards 43). */
    ReportFile exportClinicReport(LocalDate from, LocalDate to, Long doctorId, ReportFormat format);

    /**
     * Pharmacy report (FR040, UC25) for one {@code type}
     * (stock-usage|expiry|dispensing-history). Unknown types raise a 400.
     */
    PharmacyReportResponse getPharmacyReport(String type, LocalDate from, LocalDate to);

    /** Pharmacy report rendered as CSV/PDF; audited as a report export (api-standards 43). */
    ReportFile exportPharmacyReport(String type, LocalDate from, LocalDate to, ReportFormat format);
}
