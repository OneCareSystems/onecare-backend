package com.onecare.backend.chatbot.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import com.onecare.backend.chatbot.exception.InvalidInputException;
import com.onecare.backend.chatbot.exception.RateLimitExceededException;

public class PublicRateLimitInterceptor implements HandlerInterceptor {
    private final SlidingWindowRateLimiter limiter;
    private final ClientIpResolver resolver;
    private final int maxBodyBytes;

    public PublicRateLimitInterceptor(SlidingWindowRateLimiter limiter, ClientIpResolver resolver, int maxBodyBytes) {
        this.limiter = limiter;
        this.resolver = resolver;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String client = resolver.resolve(request.getRemoteAddr(), request.getHeader("X-Forwarded-For"));
        SlidingWindowRateLimiter.Decision decision = limiter.tryAcquire(ClientIpResolver.rateLimitKey(client));
        response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        if (!decision.allowed()) throw new RateLimitExceededException(decision.retryAfterSeconds());
        if (request.getContentLengthLong() > maxBodyBytes) throw new InvalidInputException("The request is too large.");
        return true;
    }
}
