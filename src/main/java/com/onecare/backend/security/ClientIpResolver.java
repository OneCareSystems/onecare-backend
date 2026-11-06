package com.onecare.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves the client IP for a request: the left-most X-Forwarded-For hop
 * when present, otherwise the socket remote address. Shared by the rate
 * limit filter and the audit trail so both record the same client identity.
 */
public final class ClientIpResolver {

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private ClientIpResolver() {
        // Utility class
    }

    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader(FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Resolves the IP of the request currently being handled, or null when
     * invoked outside a servlet request thread (e.g. async/scheduler).
     */
    public static String resolveCurrentRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        return resolve(attributes.getRequest());
    }
}
