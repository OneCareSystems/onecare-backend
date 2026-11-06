package com.onecare.backend.exception;

/**
 * Raised when a request would collide with an existing financial record
 * (409 Conflict): a second invoice for the same consultation or a
 * version conflict on an invoice.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
