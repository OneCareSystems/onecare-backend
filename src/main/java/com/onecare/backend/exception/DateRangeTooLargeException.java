package com.onecare.backend.exception;

/**
 * A report was requested over a date range wider than the allowed threshold
 * (UC24 alt flow, AC3). Mapped to 422 by the {@code GlobalExceptionHandler} so
 * the request is rejected instead of running an unbounded aggregation.
 */
public class DateRangeTooLargeException extends RuntimeException {

    public static final String DEFAULT_MESSAGE = "Date range too large, narrow your range";

    public DateRangeTooLargeException() {
        this(DEFAULT_MESSAGE);
    }

    public DateRangeTooLargeException(String message) {
        super(message);
    }
}
