package com.onecare.backend.chatbot;

import java.util.List;

/**
 * API-01: validate, forward to Django, answer. Plain class (registered as a bean in ChatApiConfig).
 * A patient never gets an error because the chatbot is down: they get the localised "temporarily unavailable"
 * message (type FALLBACK, HTTP 200). Only INVALID INPUT is an error (HTTP 400). The query text is never logged here.
 */
public class ChatQueryService {
    private final QueryInputValidator validator;
    private final ChatBackend backend;

    public ChatQueryService(QueryInputValidator validator, ChatBackend backend) {
        this.validator = validator;
        this.backend = backend;
    }

    public ChatQueryResponse answer(Object rawQuery) {
        String query = validator.sanitize(rawQuery);                          // throws InvalidInputException -> 400
        boolean tamil = ScriptLanguage.isTamil(query);
        try {
            ChatBackendResult result = backend.query(query);
            String language = result.language() != null ? result.language() : (tamil ? "TA" : "EN");
            List<ChatBackendResult.Source> sources = "ANSWER".equals(result.type()) ? result.sources() : List.of();
            return new ChatQueryResponse(result.type(), language, result.message(), sources, result.requestId());
        } catch (RuntimeException e) {                                        // Django down, timeout, bad reply, anything
            return ChatQueryResponse.unavailable(tamil);
        }
    }
}
