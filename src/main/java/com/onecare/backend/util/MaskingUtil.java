package com.onecare.backend.util;

/**
 * Deterministic partial masking of sensitive contact / ID values shown in or
 * exported from reports (SDS section 2.5.3).
 *
 * <p>The same input always produces the same masked output, so the on-screen
 * report and the CSV/PDF export of that report match exactly (AC5) while never
 * exposing the full value (AC6).</p>
 */
public final class MaskingUtil {

    /** Digits kept visible at the end of a masked value. */
    private static final int VISIBLE_SUFFIX = 2;

    /** Minimum length before which the whole value is starred out. */
    private static final int MIN_VISIBLE_LENGTH = 5;

    private MaskingUtil() {
    }

    /**
     * Masks a contact number, keeping the international prefix (or first local
     * digit) and the last two digits: {@code +94771234567} becomes
     * {@code +94*******67}, {@code 0771234567} becomes {@code 0******567}.
     * Null and blank values are returned unchanged.
     */
    public static String maskContactNo(String contactNo) {
        if (contactNo == null || contactNo.isBlank()) {
            return contactNo;
        }
        String trimmed = contactNo.trim();
        String prefix = trimmed.startsWith("+") ? trimmed.substring(0, 3) : trimmed.substring(0, 1);
        return mask(trimmed, prefix);
    }

    /**
     * Masks an ID/document number, keeping the first two and last two
     * characters: {@code 199912345678} becomes {@code 19********78}.
     * Null and blank values are returned unchanged.
     */
    public static String maskIdNumber(String idNumber) {
        if (idNumber == null || idNumber.isBlank()) {
            return idNumber;
        }
        String trimmed = idNumber.trim();
        String prefix = trimmed.substring(0, Math.min(2, trimmed.length()));
        return mask(trimmed, prefix);
    }

    private static String mask(String value, String visiblePrefix) {
        if (value.length() < MIN_VISIBLE_LENGTH) {
            return "*".repeat(value.length());
        }
        String suffix = value.substring(value.length() - VISIBLE_SUFFIX);
        int maskedCount = value.length() - visiblePrefix.length() - VISIBLE_SUFFIX;
        if (maskedCount <= 0) {
            return "*".repeat(value.length());
        }
        return visiblePrefix + "*".repeat(maskedCount) + suffix;
    }
}
