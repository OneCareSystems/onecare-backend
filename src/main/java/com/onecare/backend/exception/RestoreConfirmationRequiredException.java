package com.onecare.backend.exception;

/**
 * A restore was submitted without confirm=true. Restore is destructive
 * (UC5), so an explicit confirmation is always required.
 * Mapped to 400 with the message "Explicit confirmation required".
 */
public class RestoreConfirmationRequiredException extends RuntimeException {

    public RestoreConfirmationRequiredException() {
        super("Explicit confirmation required");
    }
}
