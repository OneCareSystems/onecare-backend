package com.onecare.backend.chatbot;

/** The chatbot could not be reached or returned an invalid response. */
public class ChatbotUnavailableException extends RuntimeException {
    public ChatbotUnavailableException(String message) {
        super(message);
    }

    public ChatbotUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
