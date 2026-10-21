package com.onecare.backend.chatbot;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;


public final class ApprovedUrlValidator {
    private static final int MAX_URL_LENGTH = 2048;
    private final List<String> approvedDomains;

    /** Entries look like "who.int" (a leading "*." or "." is accepted). A bad entry fails at startup, never silently. */
    public ApprovedUrlValidator(Collection<String> approvedDomains) {
        List<String> list = new ArrayList<>();
        if (approvedDomains != null) {
            for (String raw : approvedDomains) {
                if (raw != null && !raw.isBlank()) list.add(normalizeDomain(raw));
            }
        }
        this.approvedDomains = List.copyOf(list);
    }

    public boolean hasApprovedDomains() { return !approvedDomains.isEmpty(); }

    private static String normalizeDomain(String raw) {
        String d = raw.trim().toLowerCase(Locale.ROOT);
        if (d.startsWith("*.")) d = d.substring(2);
        else if (d.startsWith(".")) d = d.substring(1);
        if (d.endsWith(".")) d = d.substring(0, d.length() - 1);
        if (!d.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+")) {
            throw new IllegalArgumentException("Invalid approved domain in the allowlist: '" + raw + "'");
        }
        return d;
    }

    public String validate(String url) {
        if (url == null || url.isBlank() || url.length() > MAX_URL_LENGTH) throw invalid("A valid URL is required.");
        for (int i = 0; i < url.length(); i++) {                    // control characters are rejected BEFORE any trimming, which would hide them
            char c = url.charAt(i);
            if (Character.isISOControl(c) && c != '\t' && c != '\n' && c != '\r') throw invalid("The URL contains invalid characters.");
        }
        String text = url.strip();                                 // only plain whitespace padding is removed
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= 0x20 || c == 0x7F || Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c)) {
                throw invalid("The URL contains invalid characters.");
            }
        }
        URI uri;
        try {
            uri = new URI(text);                                   // rejects backslashes, <, >, spaces, bad escapes
        } catch (URISyntaxException e) {
            throw invalid("The URL is not valid.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw invalid("Only HTTPS links are accepted.");

        String authority = uri.getRawAuthority();
        if (authority == null || authority.isEmpty()) throw invalid("The URL has no host.");
        if (authority.indexOf('@') >= 0) throw invalid("Links with credentials are not accepted.");
        if (authority.startsWith("[")) throw invalid("IP addresses are not accepted; use an approved hostname.");

        String hostPart = authority;
        int colon = authority.lastIndexOf(':');
        if (colon >= 0) {
            hostPart = authority.substring(0, colon);
            String port = authority.substring(colon + 1);
            if (!port.matches("\\d{1,5}") || Integer.parseInt(port) != 443) throw invalid("Only the default HTTPS port is accepted.");
        }

        String host = toAsciiHost(hostPart);
        if (isIpLiteral(host)) throw invalid("IP addresses are not accepted; use an approved hostname.");
        if (!isApproved(host)) throw invalid("This website is not on the approved list.");

        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        String query = uri.getRawQuery();
        return "https://" + host + path + (query == null ? "" : "?" + query);
    }

    private boolean isApproved(String host) {
        for (String domain : approvedDomains) {
            if (host.equals(domain) || host.endsWith("." + domain)) return true;
        }
        return false;
    }

    private static String toAsciiHost(String host) {
        if (host.isEmpty()) throw invalid("The URL has no host.");
        if (host.indexOf('%') >= 0) throw invalid("The URL host is not valid.");
        String ascii;
        try {
            ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);   // Unicode look-alikes become xn-- and never match
        } catch (IllegalArgumentException e) {
            throw invalid("The URL host is not valid.");
        }
        ascii = ascii.toLowerCase(Locale.ROOT);
        if (ascii.endsWith(".")) ascii = ascii.substring(0, ascii.length() - 1);
        if (ascii.isEmpty() || ascii.length() > 253 || !ascii.matches("[a-z0-9-]+(\\.[a-z0-9-]+)*")) {
            throw invalid("The URL host is not valid.");
        }
        for (String label : ascii.split("\\.")) {
            if (label.length() > 63 || label.startsWith("-") || label.endsWith("-")) throw invalid("The URL host is not valid.");
        }
        return ascii;
    }

    /** Dotted decimal, and the shorthand/hex/octal forms some resolvers accept (127.1, 0x7f.0.0.1, 2130706433). */
    private static boolean isIpLiteral(String host) {
        return host.matches("(0x[0-9a-f]+|[0-9]+)(\\.(0x[0-9a-f]+|[0-9]+)){0,3}");
    }

    private static InvalidInputException invalid(String message) { return new InvalidInputException(message); }
}
