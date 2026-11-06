package com.onecare.backend.enums;

/**
 * The pharmacy report kinds understood by GET /api/reports/pharmacy?type=...
 * (FR040, UC25). Anything outside this list is an unknown report type (400).
 */
public enum ReportType {

    /** Units dispensed per medicine over the range (DDP-25 + DDP-30). */
    STOCK_USAGE,

    /** Medicines whose expiry_date falls inside the range (DDP-21 data). */
    EXPIRY,

    /** Individual dispensing events over the range (DDP-25 + DDP-30). */
    DISPENSING_HISTORY
}
