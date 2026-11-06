package com.onecare.backend.enums;

/**
 * Output format of a generated report. Omitted (or {@code json}) returns the
 * on-screen JSON payload; {@code csv} and {@code pdf} download the exact same
 * query result as a file (AC5 - both renderings derive from one query).
 */
public enum ReportFormat {
    JSON,
    CSV,
    PDF
}
