package com.onecare.backend.chatbot.query;

import java.util.List;

import com.onecare.backend.chatbot.ChatMessages;

/** What the public endpoint returns. Always type + language + message; sources only for an ANSWER. */
public record ChatQueryResponse(String type, String language, String message, List<ChatBackendResult.Source> sources, String requestId, String action) {
    public ChatQueryResponse(String type, String language, String message, List<ChatBackendResult.Source> sources, String requestId) {
        this(type, language, message, sources, requestId, null);
    }

    public static ChatQueryResponse unavailable(boolean tamil) {
        return new ChatQueryResponse("FALLBACK", tamil ? "TA" : "EN", ChatMessages.unavailable(tamil), List.of(), null);
    }
}
