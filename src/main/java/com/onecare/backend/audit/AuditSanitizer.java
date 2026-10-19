package com.onecare.backend.audit;

import java.util.regex.Pattern;

/**
 * Scrubs secret-shaped material from audit descriptions before they are
 * persisted (NFRS-006): password/secret/token style key-value pairs and
 * JWT-shaped strings are replaced with [REDACTED].
 */
public final class AuditSanitizer {

    private static final Pattern SENSITIVE_PAIR = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|secret|token|jwt|authorization|credential)s?"
                    + "\\b\\s*\"?\\s*[=:]\\s*\"?[^\\s,;&\"'}]+");

    private static final Pattern JWT_SHAPED = Pattern.compile(
            "eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    private AuditSanitizer() {
        // Utility class
    }

    public static String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String result = SENSITIVE_PAIR.matcher(text).replaceAll("$1=[REDACTED]");
        return JWT_SHAPED.matcher(result).replaceAll("[REDACTED]");
    }
}
