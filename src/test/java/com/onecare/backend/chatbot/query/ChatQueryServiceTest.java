package com.onecare.backend.chatbot.query;

import com.onecare.backend.chatbot.ChatMessages;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import com.onecare.backend.chatbot.exception.ChatbotUnavailableException;
import com.onecare.backend.chatbot.exception.InvalidInputException;

class ChatQueryServiceTest {
    private final List<String> sent = new ArrayList<>();
    private static final ChatBackendResult.Source SOURCE = new ChatBackendResult.Source(7L, "guide.pdf", null, "EN");

    private ChatQueryService service(ChatBackend backend) { return new ChatQueryService(new QueryInputValidator(500), backend); }

    private ChatBackend answering(String type, String language) {
        return text -> {
            sent.add(text);
            return new ChatBackendResult(type, language, "message", List.of(SOURCE), "0123456789abcdef");
        };
    }

    @Test void the_sanitised_text_is_what_is_sent_to_the_backend() {
        service(answering("ANSWER", "EN")).answer("  what\tis\u200B paracetamol \n");
        assertEquals(List.of("what is paracetamol"), sent);
    }

    @Test void an_answer_keeps_its_sources_and_request_id() {
        ChatQueryResponse r = service(answering("ANSWER", "EN")).answer("hello there");
        assertEquals("ANSWER", r.type());
        assertEquals(List.of(SOURCE), r.sources());
        assertEquals("0123456789abcdef", r.requestId());
    }

    @Test void sources_are_only_returned_for_an_answer() {
        for (String type : new String[] {"REFUSAL", "FALLBACK", "CLINIC_FACT"}) {
            assertTrue(service(answering(type, "EN")).answer("hello there").sources().isEmpty(), type);
        }
    }

    @Test void when_django_gives_no_language_it_is_taken_from_the_script() {
        assertEquals("TA", service(answering("ANSWER", null)).answer("பாராசிட்டமால் எதற்கு பயன்படுகிறது").language());
        assertEquals("EN", service(answering("ANSWER", null)).answer("what is paracetamol").language());
        assertEquals("EN", service(answering("ANSWER", "EN")).answer("பாராசிட்டமால்").language());       // Django's value wins
    }

    @Test void invalid_input_is_an_error_and_the_backend_is_never_called() {
        ChatQueryService s = service(answering("ANSWER", "EN"));
        for (Object bad : new Object[] {null, "", "   ", 5, List.of("a"), "x".repeat(501), "???"}) {
            assertThrows(InvalidInputException.class, () -> s.answer(bad), String.valueOf(bad));
        }
        assertTrue(sent.isEmpty());
    }

    @Test void a_backend_failure_gives_the_english_unavailable_message_not_an_error() {
        ChatQueryResponse r = service(t -> { throw new ChatbotUnavailableException("down"); }).answer("what is paracetamol");
        assertEquals("FALLBACK", r.type());
        assertEquals("EN", r.language());
        assertEquals(ChatMessages.unavailable(false), r.message());
        assertTrue(r.sources().isEmpty());
    }

    @Test void a_backend_failure_for_a_tamil_query_gives_the_tamil_message() {
        ChatQueryResponse r = service(t -> { throw new ChatbotUnavailableException("down"); }).answer("பாராசிட்டமால் எதற்கு பயன்படுகிறது");
        assertEquals("TA", r.language());
        assertEquals(ChatMessages.unavailable(true), r.message());
        assertTrue(r.message().codePoints().anyMatch(c -> c >= 0x0B80 && c <= 0x0BFF));
        assertNotEquals(ChatMessages.unavailable(false), r.message());
    }

    @Test void any_unexpected_backend_error_is_also_a_safe_response() {
        ChatQueryResponse r = service(t -> { throw new IllegalStateException("bug"); }).answer("what is paracetamol");
        assertEquals("FALLBACK", r.type());
    }

    @Test void a_malformed_django_reply_never_reaches_the_patient() {
        ChatBackend broken = t -> ChatBackendResult.fromMap(java.util.Map.of("type", "HACKED", "message", "x"));
        assertEquals("FALLBACK", service(broken).answer("what is paracetamol").type());
    }
}
