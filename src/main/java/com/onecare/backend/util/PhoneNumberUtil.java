package com.onecare.backend.util;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for validating and normalizing Sri Lankan phone numbers.
 *
 * <p>The <b>canonical storage format</b> is separator-free E.164 style:
 * {@code +94771234567}. This is the format the {@code chk_patient_contact_no}
 * CHECK constraint on {@code patients.contact_no} enforces, so every write
 * path must persist numbers in this form regardless of how the caller typed
 * them ({@code 0771234567}, {@code +94 771 234 567}, {@code 94771234567}, ...).</p>
 *
 * <p>The legacy spaced format {@code +94 771 234 567} (used before the
 * constraint was introduced) is still understood by {@link #lookupVariants} and
 * {@link #legacySpacedFormat} so already-stored rows keep matching during
 * lookups and searches.</p>
 */
public final class PhoneNumberUtil {

    private static final Pattern SL_PHONE_PATTERN = Pattern.compile("^(\\+94|0094|94|0)?([1-9]\\d{8})$");

    private PhoneNumberUtil() {}

    /**
     * Checks if the given string represents a valid Sri Lankan phone number.
     * Accepts formats such as 0771234567, +94771234567, +94 771 234 567, 94771234567.
     */
    public static boolean isValidSriLankanPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return false;
        }
        String cleaned = phone.replaceAll("[\\s\\-\\(\\)]", "");
        return SL_PHONE_PATTERN.matcher(cleaned).matches();
    }

    /**
     * Normalizes a Sri Lankan phone number into the canonical storage format:
     * {@code +94771234567} (no separators — matches {@code chk_patient_contact_no}).
     * If the input is not a recognized Sri Lankan number, the trimmed input is returned.
     */
    public static String normalizeSriLankanPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return phone;
        }
        String cleaned = phone.replaceAll("[\\s\\-\\(\\)]", "");
        Matcher matcher = SL_PHONE_PATTERN.matcher(cleaned);
        if (matcher.matches()) {
            return "+94" + matcher.group(2);
        }
        return phone.trim();
    }

    /**
     * Normalizes into the legacy spaced format {@code +94 771 234 567}.
     * Only needed to keep matching rows persisted before the canonical format was
     * introduced; never use it for new writes. Returns the trimmed input when the
     * value is not a recognized Sri Lankan number.
     */
    public static String legacySpacedFormat(String phone) {
        if (phone == null || phone.isBlank()) {
            return phone;
        }
        String nineDigits = extractNineDigits(phone);
        if (nineDigits == null) {
            return phone.trim();
        }
        return "+94 " + nineDigits.substring(0, 3) + " "
                + nineDigits.substring(3, 6) + " " + nineDigits.substring(6, 9);
    }

    /**
     * Value to persist: the canonical form when the input is a recognized Sri Lankan
     * number, otherwise the trimmed input. Never throws — malformed values are left
     * for the database constraint to reject.
     */
    public static String canonicalPhone(String phone) {
        if (phone == null) {
            return null;
        }
        String trimmed = phone.trim();
        return isValidSriLankanPhone(trimmed) ? normalizeSriLankanPhone(trimmed) : trimmed;
    }

    /**
     * Every stored-format variant worth matching against when a caller supplies a
     * phone number: canonical first, then the legacy spaced form, then the value as
     * typed (which may be a legacy raw {@code 0771234567} row), then the local
     * 10-digit form. Duplicates are removed while preserving that order.
     *
     * <p>Returns an empty list for {@code null} and a single-element list for values
     * that are not phone numbers at all, so plain text searches behave unchanged.</p>
     */
    public static List<String> lookupVariants(String phone) {
        if (phone == null) {
            return List.of();
        }
        String trimmed = phone.trim();
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        if (!trimmed.isEmpty()) {
            if (isValidSriLankanPhone(trimmed)) {
                variants.add(normalizeSriLankanPhone(trimmed));
                variants.add(legacySpacedFormat(trimmed));
            }
            variants.add(trimmed);
            String nineDigits = extractNineDigits(trimmed);
            if (nineDigits != null) {
                variants.add("0" + nineDigits);
            }
        }
        return List.copyOf(variants);
    }

    /**
     * Extracts the 9 subscriber digits (without leading zero or country code).
     * e.g., 0771234567 -> 771234567.
     */
    public static String extractNineDigits(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String cleaned = phone.replaceAll("[\\s\\-\\(\\)]", "");
        Matcher matcher = SL_PHONE_PATTERN.matcher(cleaned);
        if (matcher.matches()) {
            return matcher.group(2);
        }
        return null;
    }

    /**
     * Validates and normalizes to the canonical storage format.
     * If invalid, throws IllegalArgumentException.
     */
    public static String validateAndNormalize(String phone) {
        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("Contact number is required");
        }
        if (!isValidSriLankanPhone(phone)) {
            throw new IllegalArgumentException(
                    "Invalid Sri Lankan phone number format. Expected format: +94771234567 or standard 10-digit format (e.g. 0771234567)");
        }
        return normalizeSriLankanPhone(phone);
    }
}
