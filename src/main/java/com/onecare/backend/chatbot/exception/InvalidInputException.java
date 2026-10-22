package com.onecare.backend.chatbot.exception;

/** The caller sent something invalid (bad query, bad URL, oversize request). Mapped to HTTP 400. No Spring dependency. */
public class InvalidInputException extends RuntimeException {
    public InvalidInputException(String message) { super(message); }
}
