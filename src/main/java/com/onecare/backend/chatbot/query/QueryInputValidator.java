package com.onecare.backend.chatbot.query;

import java.text.Normalizer;

import com.onecare.backend.chatbot.exception.InvalidInputException;

/**
 * API-03: validates and cleans the free-text patient query. No Spring dependency, so it is unit-tested without the app.
 *
 * What it does, in order:
 *   1. rejects anything that is not a JSON string (number, boolean, list, object, null)        -> "non-text payload"
 *   2. rejects malformed Unicode (a lone surrogate)
 *   3. normalises to NFC (the same form Django uses, so Tamil text compares equal on both sides)
 *   4. removes invisible/control characters that are used to hide or spoof text:
 *        control characters, bidi overrides (U+202A-202E, U+2066-2069), zero-width space, word joiner, BOM,
 *        soft hyphen, the Unicode "tag" characters (U+E0000-E007F) and variation selectors (U+E0100-E01EF)
 *      It KEEPS U+200C (ZWNJ) and U+200D (ZWJ), which are legitimate in Indic scripts, and every Tamil letter and sign.
 *   5. collapses every run of whitespace (tabs, newlines, NBSP ...) into one space and trims
 *   6. enforces the length cap in Unicode CODE POINTS (not UTF-16 units), so a Tamil query is not penalised
 *   7. requires at least one letter or digit (emoji or punctuation only is not a question)
 * It does NOT strip HTML or quotes: "<5 mg" is legitimate text, and the query is never rendered as HTML by the server.
 */
public final class QueryInputValidator {
    private final int maxChars;

    public QueryInputValidator(int maxChars) {
        if (maxChars < 1) throw new IllegalArgumentException("maxChars must be at least 1");
        this.maxChars = maxChars;
    }

    public int maxChars() { return maxChars; }

    /** Returns the cleaned query, or throws InvalidInputException (HTTP 400). */
    public String sanitize(Object raw) {
        if (raw == null) throw new InvalidInputException("'query' is required.");
        if (!(raw instanceof String text)) throw new InvalidInputException("'query' must be text.");
        if (text.length() > maxChars * 4L + 64) throw tooLong();              // cheap guard before any processing
        rejectLoneSurrogates(text);

        String normalized = Normalizer.normalize(text, Normalizer.Form.NFC);
        StringBuilder out = new StringBuilder(normalized.length());
        boolean pendingSpace = false;
        for (int i = 0; i < normalized.length(); ) {
            int cp = normalized.codePointAt(i);
            i += Character.charCount(cp);
            if (isSpace(cp)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (isRemoved(cp)) continue;
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.appendCodePoint(cp);
        }

        String cleaned = out.toString();
        if (cleaned.isEmpty()) throw new InvalidInputException("'query' is required.");
        if (cleaned.codePointCount(0, cleaned.length()) > maxChars) throw tooLong();
        if (cleaned.codePoints().noneMatch(Character::isLetterOrDigit)) throw new InvalidInputException("'query' must contain text.");
        return cleaned;
    }

    private InvalidInputException tooLong() {
        return new InvalidInputException("'query' must be at most " + maxChars + " characters.");
    }

    private static void rejectLoneSurrogates(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) throw badUnicode();
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw badUnicode();
            }
        }
    }

    private static InvalidInputException badUnicode() {
        return new InvalidInputException("'query' contains invalid characters.");
    }

    private static boolean isSpace(int cp) {
        return cp == ' ' || Character.isWhitespace(cp) || Character.isSpaceChar(cp);
    }

    private static boolean isRemoved(int cp) {
        if (cp == 0x200C || cp == 0x200D) return false;                          // ZWNJ / ZWJ are valid in Indic text
        if (cp >= 0xE0100 && cp <= 0xE01EF) return true;                         // variation selectors supplement
        int type = Character.getType(cp);
        return type == Character.CONTROL || type == Character.FORMAT || type == Character.PRIVATE_USE;
    }
}
