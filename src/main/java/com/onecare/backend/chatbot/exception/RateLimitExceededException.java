package com.onecare.backend.chatbot.exception;

/** Too many requests from one client. Mapped to HTTP 429 with a Retry-After header. */
public class RateLimitExceededException extends RuntimeException {
    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        super("Too many requests.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
