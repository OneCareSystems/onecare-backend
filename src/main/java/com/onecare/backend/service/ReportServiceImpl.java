package com.onecare.backend.service;

import com.onecare.backend.audit.Audited;
import com.onecare.backend.dto.response.ClinicReportResponse;
import com.onecare.backend.dto.response.PharmacyReportResponse;
import com.onecare.backend.dto.response.PharmacyReportResponse.DispensingRow;
import com.onecare.backend.dto.response.PharmacyReportResponse.ExpiryRow;
import com.onecare.backend.dto.response.PharmacyReportResponse.StockUsageRow;
import com.onecare.backend.entity.Medicine;
import com.onecare.backend.enums.AppointmentStatus;
import com.onecare.backend.enums.ReportFormat;
import com.onecare.backend.enums.ReportType;
import com.onecare.backend.enums.Status;
import com.onecare.backend.exception.BusinessRuleException;
import com.onecare.backend.exception.DateRangeTooLargeException;
import com.onecare.backend.repository.AppointmentRepository;
import com.onecare.backend.repository.DispensingItemRepository;
import com.onecare.backend.repository.MedicineRepository;
import com.onecare.backend.repository.PrescriptionItemRepository;
import com.onecare.backend.util.MaskingUtil;
import com.onecare.backend.util.ReportExportUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Report generation (FR039/FR040). Strictly read-only aggregation over the
 * data DDP-20/21/22/25/30 own - no write paths and no duplicated business
 * logic. Every report and its CSV/PDF export derive from the same repository
 * query (AC5), and contact values are masked once at mapping time so screen
 * and export match exactly (AC5/AC6).
 */
@Service
@Transactional(readOnly = true)
public class ReportServiceImpl implements ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportServiceImpl.class);

    /** UC24 alt flow (AC3): reject anything wider than one year with 422. */
    static final long MAX_RANGE_DAYS = 365;

    /** Statuses that actually handed medicine out (DDP-25). */
    private static final List<Status> HANDED_OVER_STATUSES =
            List.of(Status.DISPENSED, Status.PARTIALLY_DISPENSED);

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final AppointmentRepository appointmentRepository;
    private final MedicineRepository medicineRepository;
    private final DispensingItemRepository dispensingItemRepository;
    private final PrescriptionItemRepository prescriptionItemRepository;

    public ReportServiceImpl(AppointmentRepository appointmentRepository,
                             MedicineRepository medicineRepository,
                             DispensingItemRepository dispensingItemRepository,
                             PrescriptionItemRepository prescriptionItemRepository) {
        this.appointmentRepository = appointmentRepository;
        this.medicineRepository = medicineRepository;
        this.dispensingItemRepository = dispensingItemRepository;
        this.prescriptionItemRepository = prescriptionItemRepository;
    }

    @Override
    public ClinicReportResponse getClinicReport(LocalDate from, LocalDate to, Long doctorId) {
        validateRange(from, to);
        return buildClinicReport(from, to, doctorId);
    }

    @Override
    @Audited(action = "REPORT_EXPORT", module = "REPORT")
    public ReportFile exportClinicReport(LocalDate from, LocalDate to, Long doctorId,
                                         ReportFormat format) {
        validateRange(from, to);
        ClinicReportResponse report = buildClinicReport(from, to, doctorId);
        String title = "Clinic Report (" + from + " to " + to + ")";
        return renderFile(title, clinicReportHeaders(), clinicReportTable(report),
                "clinic-report_" + FILE_DATE.format(from) + "_" + FILE_DATE.format(to), format);
    }

    @Override
    public PharmacyReportResponse getPharmacyReport(String type, LocalDate from, LocalDate to) {
        validateRange(from, to);
        return buildPharmacyReport(parseReportType(type), from, to);
    }

    @Override
    @Audited(action = "REPORT_EXPORT", module = "REPORT")
    public ReportFile exportPharmacyReport(String type, LocalDate from, LocalDate to,
                                           ReportFormat format) {
        validateRange(from, to);
        ReportType reportType = parseReportType(type);
        PharmacyReportResponse report = buildPharmacyReport(reportType, from, to);
        String title = "Pharmacy Report - " + reportType.name().replace('_', ' ')
                + " (" + from + " to " + to + ")";
        String baseName = "pharmacy-report_" + reportType.name().toLowerCase(Locale.ROOT).replace('_', '-')
                + "_" + FILE_DATE.format(from) + "_" + FILE_DATE.format(to);
        return renderFile(title, pharmacyReportHeaders(reportType), pharmacyReportTable(report),
                baseName, format);
    }

    // ------------------------------------------------------------------
    // Query building (single source for screen AND export - AC5)
    // ------------------------------------------------------------------

    private ClinicReportResponse buildClinicReport(LocalDate from, LocalDate to, Long doctorId) {
        Map<AppointmentStatus, Long> byStatus = new EnumMap<>(AppointmentStatus.class);
        List<Object[]> statusCounts = doctorId == null
                ? appointmentRepository.countByStatusBetween(from, to)
                : appointmentRepository.countByStatusBetweenForDoctor(from, to, doctorId);
        long total = 0;
        for (Object[] row : statusCounts) {
            AppointmentStatus status = (AppointmentStatus) row[0];
            long count = ((Number) row[1]).longValue();
            byStatus.put(status, count);
            total += count;
        }
        long uniquePatients = doctorId == null
                ? appointmentRepository.countDistinctPatientsBetween(from, to)
                : appointmentRepository.countDistinctPatientsBetweenForDoctor(from, to, doctorId);

        return new ClinicReportResponse(
                from, to, doctorId,
                total,
                byStatus.getOrDefault(AppointmentStatus.COMPLETED, 0L),
                byStatus.getOrDefault(AppointmentStatus.SCHEDULED, 0L),
                byStatus.getOrDefault(AppointmentStatus.ARRIVED, 0L),
                byStatus.getOrDefault(AppointmentStatus.COMPLETED, 0L),
                byStatus.getOrDefault(AppointmentStatus.CANCELLED, 0L),
                byStatus.getOrDefault(AppointmentStatus.NO_SHOW, 0L),
                uniquePatients,
                LocalDateTime.now());
    }

    private PharmacyReportResponse buildPharmacyReport(ReportType type, LocalDate from, LocalDate to) {
        return switch (type) {
            case STOCK_USAGE -> new PharmacyReportResponse(type, from, to,
                    buildStockUsage(from, to), List.of(), List.of());
            case EXPIRY -> new PharmacyReportResponse(type, from, to,
                    List.of(), buildExpiry(from, to), List.of());
            case DISPENSING_HISTORY -> new PharmacyReportResponse(type, from, to,
                    List.of(), List.of(), buildDispensingHistory(from, to));
        };
    }

    /**
     * Units dispensed per medicine from both dispensing flows: external
     * handovers (DDP-25) plus in-house dispensing (DDP-30, prescriptions
     * marked DISPENSED with no linked external event, so nothing is counted
     * twice). A single grouped query per source - no N+1.
     */
    private List<StockUsageRow> buildStockUsage(LocalDate from, LocalDate to) {
        Map<Long, StockUsageRow> byMedicine = new HashMap<>();
        for (Object[] row : dispensingItemRepository.aggregateStockUsage(
                from.atStartOfDay(), to.plusDays(1).atStartOfDay(), HANDED_OVER_STATUSES)) {
            Medicine medicine = (Medicine) row[0];
            byMedicine.put(medicine.getMedicineId(), new StockUsageRow(
                    medicine.getMedicineId(), medicine.getName(), ((Number) row[1]).longValue()));
        }
        for (Object[] row : prescriptionItemRepository.aggregateDispensedStockUsage(from, to)) {
            Medicine medicine = (Medicine) row[0];
            long units = ((Number) row[1]).longValue();
            byMedicine.compute(medicine.getMedicineId(), (id, existing) -> existing == null
                    ? new StockUsageRow(id, medicine.getName(), units)
                    : new StockUsageRow(id, existing.medicineName(), existing.unitsDispensed() + units));
        }
        return byMedicine.values().stream()
                .sorted(Comparator.comparingLong(StockUsageRow::unitsDispensed).reversed()
                        .thenComparing(StockUsageRow::medicineName))
                .toList();
    }

    /** Straight projection of the DDP-21 expiry data - no separate expiry logic (AC4). */
    private List<ExpiryRow> buildExpiry(LocalDate from, LocalDate to) {
        return medicineRepository.findExpiringBetween(from, to).stream()
                .map(medicine -> new ExpiryRow(
                        medicine.getMedicineId(),
                        medicine.getName(),
                        medicine.getExpiryDate(),
                        medicine.getStockQuantity(),
                        Boolean.TRUE.equals(medicine.getIsQuarantined())))
                .toList();
    }

    /** Both dispensing flows: in-house (DDP-30) and external (DDP-25), contacts masked (AC6). */
    private List<DispensingRow> buildDispensingHistory(LocalDate from, LocalDate to) {
        List<DispensingRow> rows = new ArrayList<>();
        for (Object[] row : dispensingItemRepository.findDispensingHistoryRows(
                from.atStartOfDay(), to.plusDays(1).atStartOfDay(), HANDED_OVER_STATUSES)) {
            rows.add(new DispensingRow(
                    (Long) row[0], "EXTERNAL", (LocalDateTime) row[1],
                    (Long) row[2], (String) row[3], MaskingUtil.maskContactNo((String) row[4]),
                    (String) row[5], ((Number) row[6]).intValue()));
        }
        for (Object[] row : prescriptionItemRepository.findDispensedItemRows(from, to)) {
            rows.add(new DispensingRow(
                    (Long) row[0], "IN-HOUSE", ((LocalDate) row[1]).atStartOfDay(),
                    (Long) row[2], (String) row[3], MaskingUtil.maskContactNo((String) row[4]),
                    (String) row[5], ((Number) row[6]).intValue()));
        }
        rows.sort(Comparator.comparing(DispensingRow::dispenseDate).reversed());
        return List.copyOf(rows);
    }

    // ------------------------------------------------------------------
    // Rendering (CSV/PDF from the same payload - AC5)
    // ------------------------------------------------------------------

    private List<List<String>> clinicReportTable(ClinicReportResponse report) {
        return List.of(List.of(
                String.valueOf(report.from()),
                String.valueOf(report.to()),
                report.doctorId() == null ? "ALL" : String.valueOf(report.doctorId()),
                String.valueOf(report.totalAppointments()),
                String.valueOf(report.consultationCount()),
                String.valueOf(report.scheduled()),
                String.valueOf(report.arrived()),
                String.valueOf(report.completed()),
                String.valueOf(report.cancelled()),
                String.valueOf(report.noShow()),
                String.valueOf(report.uniquePatients()),
                String.valueOf(report.generatedAt())));
    }

    private List<String> clinicReportHeaders() {
        return List.of("From", "To", "Doctor ID", "Total Appointments", "Consultations",
                "Scheduled", "Arrived", "Completed", "Cancelled", "No Show",
                "Unique Patients", "Generated At");
    }

    private List<String> pharmacyReportHeaders(ReportType type) {
        return switch (type) {
            case STOCK_USAGE -> List.of("Medicine ID", "Medicine", "Units Dispensed");
            case EXPIRY -> List.of("Medicine ID", "Medicine", "Expiry Date",
                    "Stock Quantity", "Quarantined");
            case DISPENSING_HISTORY -> List.of("Reference ID", "Source", "Dispense Date",
                    "Patient ID", "Patient Name", "Patient Contact (Masked)", "Medicine", "Quantity");
        };
    }

    private List<List<String>> pharmacyReportTable(PharmacyReportResponse report) {
        return switch (report.type()) {
            case STOCK_USAGE -> report.stockUsage().stream()
                    .map(row -> List.of(
                            String.valueOf(row.medicineId()),
                            row.medicineName(),
                            String.valueOf(row.unitsDispensed())))
                    .toList();
            case EXPIRY -> report.expiry().stream()
                    .map(row -> List.of(
                            String.valueOf(row.medicineId()),
                            row.medicineName(),
                            String.valueOf(row.expiryDate()),
                            String.valueOf(row.stockQuantity()),
                            String.valueOf(row.quarantined())))
                    .toList();
            case DISPENSING_HISTORY -> report.dispensingHistory().stream()
                    .map(row -> List.of(
                            String.valueOf(row.referenceId()),
                            row.source(),
                            String.valueOf(row.dispenseDate()),
                            row.patientId() == null ? "" : String.valueOf(row.patientId()),
                            row.patientName() == null ? "" : row.patientName(),
                            row.patientContactNo() == null ? "" : row.patientContactNo(),
                            row.medicineName(),
                            String.valueOf(row.quantity())))
                    .toList();
        };
    }

    private ReportFile renderFile(String title, List<String> headers, List<List<String>> rows,
                                  String baseName, ReportFormat format) {
        return switch (format) {
            case CSV -> new ReportFile(baseName + ".csv", "text/csv",
                    ReportExportUtil.toCsv(headers, rows));
            case PDF -> new ReportFile(baseName + ".pdf", "application/pdf",
                    ReportExportUtil.toPdf(title, headers, rows));
            case JSON -> throw new BusinessRuleException(
                    "format=csv or format=pdf is required for exports");
        };
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    /** UC24: required, ordered and bounded date ranges before any query runs. */
    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new BusinessRuleException("Both 'from' and 'to' dates are required");
        }
        if (from.isAfter(to)) {
            throw new BusinessRuleException("'from' date must not be after 'to' date");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new DateRangeTooLargeException();
        }
    }

    /** Unknown pharmacy type is a 400 (AC2); the raw string never reaches a query. */
    private ReportType parseReportType(String type) {
        if (type == null || type.isBlank()) {
            throw new BusinessRuleException("Unknown report type");
        }
        try {
            // accept both API-style dashes (stock-usage) and enum-style underscores
            return ReportType.valueOf(type.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            log.debug("Unknown pharmacy report type requested: {}", type);
            throw new BusinessRuleException("Unknown report type");
        }
    }
}
