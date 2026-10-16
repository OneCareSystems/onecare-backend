package com.onecare.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window rate limiter with two independent tiers (SDS 2.5.4, NFRS-009):
 * - auth tier: /api/auth/* endpoints
 * - public-booking tier: /api/public/* endpoints (DDP-33 — public booking is
 *   an abuse vector, so it gets its own limit, distinct from the auth tier)
 * Each tier has its own limit/window configuration and on/off switch. Within a
 * tier, buckets are scoped per client IP *and* per endpoint, so traffic on one
 * route cannot consume another route's allowance. In-memory and per-instance:
 * adequate for a single node, but counters are not shared across instances
 * (documented limitation).
 */
@Component
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiRateLimitFilter.class);
    private static final String AUTH_PREFIX = "/api/auth/";
    private static final String PUBLIC_PREFIX = "/api/public/";
    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final int MAX_TRACKED_BUCKETS = 10_000;

    private record Window(long startedAtMillis, int count) {
    }

    /** Per-tier limit configuration. */
    private record RateLimitTier(String name, boolean enabled, int requestsPerWindow, long windowMillis) {
    }

    private final RateLimitTier authTier;
    private final RateLimitTier publicBookingTier;
    private final long maxWindowMillis;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public ApiRateLimitFilter(
            @Value("${auth.rate-limit.enabled:true}") boolean authEnabled,
            @Value("${auth.rate-limit.requests-per-minute:10}") int authRequestsPerWindow,
            @Value("${auth.rate-limit.window-seconds:60}") long authWindowSeconds,
            @Value("${public-booking.rate-limit.enabled:true}") boolean publicBookingEnabled,
            @Value("${public-booking.rate-limit.requests-per-minute:5}") int publicBookingRequestsPerWindow,
            @Value("${public-booking.rate-limit.window-seconds:60}") long publicBookingWindowSeconds) {
        this.authTier = new RateLimitTier("auth", authEnabled, authRequestsPerWindow, authWindowSeconds * 1000L);
        this.publicBookingTier = new RateLimitTier("public-booking", publicBookingEnabled,
                publicBookingRequestsPerWindow, publicBookingWindowSeconds * 1000L);
        this.maxWindowMillis = Math.max(authTier.windowMillis(), publicBookingTier.windowMillis());
    }

    /** Convenience constructor applying the same config to both tiers (tests). */
    public ApiRateLimitFilter(boolean enabled, int requestsPerWindow, long windowSeconds) {
        this(enabled, requestsPerWindow, windowSeconds, enabled, requestsPerWindow, windowSeconds);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        RateLimitTier tier = tierFor(request.getRequestURI());
        if (tier == null || !tier.enabled()) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(request);
        String bucketKey = rateLimitKey(request, clientIp);
        long now = System.currentTimeMillis();

        Window window = windows.compute(bucketKey, (key, current) -> {
            if (current == null || now - current.startedAtMillis() >= tier.windowMillis()) {
                purgeExpired(now);
                return new Window(now, 1);
            }
            return new Window(current.startedAtMillis(), current.count() + 1);
        });

        if (window.count() > tier.requestsPerWindow()) {
            long retryAfterSeconds = Math.max(
                    1, (window.startedAtMillis() + tier.windowMillis() - now) / 1000);
            log.warn("Rate limit exceeded | tier={} | ip={} | path={} | limit={}/{}s",
                    tier.name(), clientIp, request.getRequestURI(),
                    tier.requestsPerWindow(), tier.windowMillis / 1000);

            response.setStatus(429);
            response.setContentType("application/json");
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.getWriter().write(
                    "{\"success\":false,\"message\":\"Too many requests. Please try again later.\",\"data\":null}");
            return;
        }

        chain.doFilter(request, response);
    }

    private RateLimitTier tierFor(String requestUri) {
        if (requestUri.startsWith(AUTH_PREFIX)) {
            return authTier;
        }
        if (requestUri.startsWith(PUBLIC_PREFIX)) {
            return publicBookingTier;
        }
        return null;
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
            if (now - iterator.next().getValue().startedAtMillis() >= maxWindowMillis) {
                iterator.remove();
            }
        }
    }
}
