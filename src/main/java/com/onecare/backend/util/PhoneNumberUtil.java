package com.onecare.backend.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for validating and normalizing Sri Lankan phone numbers to the agreed format:
 * +94 XXX XXX XXX
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
     * Normalizes a Sri Lankan phone number into the standard format: +94 XXX XXX XXX.
     * If the input is not a recognized Sri Lankan number, the trimmed input is returned.
     */
    public static String normalizeSriLankanPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return phone;
        }
        String cleaned = phone.replaceAll("[\\s\\-\\(\\)]", "");
        Matcher matcher = SL_PHONE_PATTERN.matcher(cleaned);
        if (matcher.matches()) {
            String nineDigits = matcher.group(2);
            return "+94 " + nineDigits.substring(0, 3) + " " + nineDigits.substring(3, 6) + " " + nineDigits.substring(6, 9);
        }
        return phone.trim();
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
     * Validates and normalizes. If invalid, throws IllegalArgumentException.
     */
    public static String validateAndNormalize(String phone) {
        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("Contact number is required");
        }
        if (!isValidSriLankanPhone(phone)) {
            throw new IllegalArgumentException(
                    "Invalid Sri Lankan phone number format. Expected format: +94 XXX XXX XXX or standard 10-digit format (e.g. 0771234567)");
        }
        return normalizeSriLankanPhone(phone);
    }
}
