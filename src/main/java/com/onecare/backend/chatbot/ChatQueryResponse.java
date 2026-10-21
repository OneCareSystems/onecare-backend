package com.onecare.backend.chatbot;

import java.util.List;

/** What the public endpoint returns. Always type + language + message; sources only for an ANSWER. */
public record ChatQueryResponse(String type, String language, String message, List<ChatBackendResult.Source> sources, String requestId) {
    public static ChatQueryResponse unavailable(boolean tamil) {
        return new ChatQueryResponse("FALLBACK", tamil ? "TA" : "EN", ChatMessages.unavailable(tamil), List.of(), null);
    }
}
