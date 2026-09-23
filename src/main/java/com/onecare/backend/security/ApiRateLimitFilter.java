package com.onecare.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window rate limiter for /api/auth/* endpoints (SDS 2.5.4).
 * Buckets are scoped per client IP *and* per endpoint, so traffic on one
 * route (e.g. /login) cannot consume another route's allowance
 * (e.g. /forgot-password). In-memory and per-instance: adequate for a
 * single node, but counters are not shared across instances (documented
 * limitation).
 */
@Component
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiRateLimitFilter.class);
    private static final String AUTH_PREFIX = "/api/auth/";
    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final int MAX_TRACKED_BUCKETS = 10_000;

    private record Window(long startedAtMillis, int count) {
    }

    private final boolean enabled;
    private final int requestsPerWindow;
    private final long windowMillis;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public ApiRateLimitFilter(
            @Value("${auth.rate-limit.enabled:true}") boolean enabled,
            @Value("${auth.rate-limit.requests-per-minute:10}") int requestsPerWindow,
            @Value("${auth.rate-limit.window-seconds:60}") long windowSeconds) {
        this.enabled = enabled;
        this.requestsPerWindow = requestsPerWindow;
        this.windowMillis = windowSeconds * 1000L;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        if (!enabled || !request.getRequestURI().startsWith(AUTH_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(request);
        String bucketKey = rateLimitKey(request, clientIp);
        long now = System.currentTimeMillis();

        Window window = windows.compute(bucketKey, (key, current) -> {
            if (current == null || now - current.startedAtMillis() >= windowMillis) {
                purgeExpired(now);
                return new Window(now, 1);
            }
            return new Window(current.startedAtMillis(), current.count() + 1);
        });

        if (window.count() > requestsPerWindow) {
            long retryAfterSeconds = Math.max(
                    1, (window.startedAtMillis() + windowMillis - now) / 1000);
            log.warn("Rate limit exceeded | ip={} | path={} | limit={}/{}s",
                    clientIp, request.getRequestURI(), requestsPerWindow, windowMillis / 1000);

            response.setStatus(429);
            response.setContentType("application/json");
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.getWriter().write(
                    "{\"success\":false,\"message\":\"Too many requests. Please try again later.\",\"data\":null}");
            return;
        }

        chain.doFilter(request, response);
    }

    private String rateLimitKey(HttpServletRequest request, String clientIp) {
        return clientIp + "|" + request.getRequestURI();
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader(FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    private void purgeExpired(long now) {
        if (windows.size() <= MAX_TRACKED_BUCKETS) {
            return;
        }
        Iterator<Map.Entry<String, Window>> iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue().startedAtMillis() >= windowMillis) {
                iterator.remove();
            }
        }
    }
}
