package com.onecare.backend.chatbot.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.onecare.backend.chatbot.exception.ChatbotUnavailableException;


public record ChatBackendResult(String type, String language, String message, List<Source> sources, String requestId) {
    public record Source(Long documentId, String source, String url, String language) {}

    private static final Set<String> TYPES = Set.of("ANSWER", "REFUSAL", "FALLBACK", "CLINIC_FACT");
    private static final int MAX_SOURCES = 5;

    public static ChatBackendResult fromMap(Map<?, ?> body) {
        if (body == null) throw bad("an empty response");
        if (!(body.get("type") instanceof String type) || !TYPES.contains(type)) throw bad("an unknown response type");
        if (!(body.get("message") instanceof String message) || message.isBlank()) throw bad("an empty message");
        Object lang = body.get("language");
        String language = "EN".equals(lang) || "TA".equals(lang) ? (String) lang : null;
        String requestId = body.get("requestId") instanceof String id && id.matches("[0-9a-fA-F]{8,64}") ? id : null;
        return new ChatBackendResult(type, language, message, parseSources(body.get("sources")), requestId);
    }

    private static List<Source> parseSources(Object raw) {
        List<Source> sources = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return sources;
        for (Object item : list) {
            if (sources.size() >= MAX_SOURCES) break;
            if (!(item instanceof Map<?, ?> map) || !(map.get("documentId") instanceof Number id)) continue;
            String source = map.get("source") instanceof String s ? s : "";
            String url = map.get("url") instanceof String u && u.startsWith("https://") ? u : null;   // links must be HTTPS
            String lang = map.get("language") instanceof String l ? l : null;
            sources.add(new Source(id.longValue(), source, url, lang));
        }
        return sources;
    }

    private static ChatbotUnavailableException bad(String what) {
        return new ChatbotUnavailableException("The chatbot service returned " + what + ".");
    }
}
