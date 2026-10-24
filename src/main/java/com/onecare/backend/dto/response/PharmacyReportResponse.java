package com.onecare.backend.dto.response;

import com.onecare.backend.enums.ReportType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Pharmacy report payload (FR040, UC25). Exactly one section is populated for
 * the requested {@code type}; the others are empty lists.
 *
 * <p>Contact values are partially masked at query-mapping time (SDS section
 * 2.5.3, AC6), so the on-screen JSON and the CSV/PDF export of the same query
 * match exactly (AC5).</p>
 */
public record PharmacyReportResponse(
        ReportType type,
        LocalDate from,
        LocalDate to,
        List<StockUsageRow> stockUsage,
        List<ExpiryRow> expiry,
        List<DispensingRow> dispensingHistory) {

    /** Units dispensed per medicine (in-house DDP-30 + external DDP-25). */
    public record StockUsageRow(
            Long medicineId,
            String medicineName,
            long unitsDispensed) {
    }

    /**
     * Medicines expiring inside the range - a straight projection of the
     * expiry_date / is_quarantined data DDP-21 maintains (AC4); no separate
     * expiry or quarantine logic runs here.
     */
    public record ExpiryRow(
            Long medicineId,
            String medicineName,
            LocalDate expiryDate,
            int stockQuantity,
            boolean quarantined) {
    }

    /** One dispensing line of the history (source distinguishes DDP-30/DDP-25). */
    public record DispensingRow(
            Long referenceId,
            String source,
            LocalDateTime dispenseDate,
            Long patientId,
            String patientName,
            String patientContactNo,
            String medicineName,
            int quantity) {
    }
}
