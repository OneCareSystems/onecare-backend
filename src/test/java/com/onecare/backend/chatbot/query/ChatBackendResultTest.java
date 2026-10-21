package com.onecare.backend.chatbot.query;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import com.onecare.backend.chatbot.exception.ChatbotUnavailableException;

class ChatBackendResultTest {
    private static Map<String, Object> answer() {
        Map<String, Object> m = new HashMap<>();
        m.put("success", true);
        m.put("type", "ANSWER");
        m.put("language", "EN");
        m.put("message", "Paracetamol relieves pain.");
        m.put("requestId", "0123456789abcdef0123456789abcdef");
        m.put("reason", "INTERNAL");
        m.put("sources", List.of(Map.of("documentId", 7, "source", "guide.pdf", "language", "EN", "score", 0.93)));
        return m;
    }

    @Test void a_valid_answer_is_parsed_and_internal_fields_are_dropped() {
        ChatBackendResult r = ChatBackendResult.fromMap(answer());
        assertEquals("ANSWER", r.type());
        assertEquals("EN", r.language());
        assertEquals("Paracetamol relieves pain.", r.message());
        assertEquals("0123456789abcdef0123456789abcdef", r.requestId());
        assertEquals(List.of(new ChatBackendResult.Source(7L, "guide.pdf", null, "EN")), r.sources());
    }

    @Test void all_four_types_are_accepted() {
        for (String type : new String[] {"ANSWER", "REFUSAL", "FALLBACK", "CLINIC_FACT"}) {
            Map<String, Object> m = answer();
            m.put("type", type);
            assertEquals(type, ChatBackendResult.fromMap(m).type());
        }
    }

    @Test void an_unknown_type_is_treated_as_unavailable() {
        for (Object bad : new Object[] {"HACKED", "answer", null, 5}) {
            Map<String, Object> m = answer();
            m.put("type", bad);
            assertThrows(ChatbotUnavailableException.class, () -> ChatBackendResult.fromMap(m));
        }
    }

    @Test void a_missing_or_blank_message_is_treated_as_unavailable() {
        for (Object bad : new Object[] {null, "", "   ", 42}) {
            Map<String, Object> m = answer();
            m.put("message", bad);
            assertThrows(ChatbotUnavailableException.class, () -> ChatBackendResult.fromMap(m));
        }
        assertThrows(ChatbotUnavailableException.class, () -> ChatBackendResult.fromMap(null));
    }

    @Test void an_invalid_language_becomes_null_so_the_service_can_decide() {
        for (Object bad : new Object[] {"FR", "en", null, 3}) {
            Map<String, Object> m = answer();
            m.put("language", bad);
            assertNull(ChatBackendResult.fromMap(m).language());
        }
    }

    @Test void only_https_links_survive_in_sources() {
        Map<String, Object> m = answer();
        m.put("sources", List.of(
                Map.of("documentId", 1, "source", "a", "url", "https://who.int/x"),
                Map.of("documentId", 2, "source", "b", "url", "javascript:alert(1)"),
                Map.of("documentId", 3, "source", "c", "url", "http://who.int/x"),
                Map.of("documentId", 4, "source", "d", "url", "")));
        List<ChatBackendResult.Source> sources = ChatBackendResult.fromMap(m).sources();
        assertEquals("https://who.int/x", sources.get(0).url());
        assertNull(sources.get(1).url());
        assertNull(sources.get(2).url());
        assertNull(sources.get(3).url());
    }

    @Test void malformed_source_entries_are_skipped_and_the_count_is_capped() {
        Map<String, Object> m = answer();
        List<Object> list = new java.util.ArrayList<>(List.of("not a map", Map.of("documentId", "7"), Map.of("source", "x")));
        for (int i = 0; i < 10; i++) list.add(Map.of("documentId", i, "source", "s" + i));
        m.put("sources", list);
        assertEquals(5, ChatBackendResult.fromMap(m).sources().size());
        m.put("sources", "nonsense");
        assertTrue(ChatBackendResult.fromMap(m).sources().isEmpty());
        m.remove("sources");
        assertTrue(ChatBackendResult.fromMap(m).sources().isEmpty());
    }

    @Test void a_bad_request_id_is_dropped() {
        for (Object bad : new Object[] {"<script>", "xyz", "short", "a".repeat(65), 5, null}) {
            Map<String, Object> m = answer();
            m.put("requestId", bad);
            assertNull(ChatBackendResult.fromMap(m).requestId(), String.valueOf(bad));
        }
    }
}
