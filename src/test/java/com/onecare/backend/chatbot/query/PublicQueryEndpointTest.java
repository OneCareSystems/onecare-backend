package com.onecare.backend.chatbot.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.onecare.backend.chatbot.exception.ChatbotUnavailableException;
import com.onecare.backend.chatbot.ratelimit.SlidingWindowRateLimiter;

@SpringBootTest
@AutoConfigureMockMvc
class PublicQueryEndpointTest {
    private static final String URL = "/api/chatbot/query";
    private static final String DATA = "$.data";        // ADAPT: where YOUR ApiResponse puts the payload ("$" if it does not wrap it)

    @Autowired MockMvc mvc;
    @Autowired SlidingWindowRateLimiter limiter;
    @MockBean ChatBackend backend;                        // replaces the real Django client

    @BeforeEach void reset() {
        limiter.clear();
        when(backend.query(anyString())).thenAnswer(i -> new ChatBackendResult("ANSWER", "EN", "Paracetamol relieves pain.",
                List.of(new ChatBackendResult.Source(7L, "guide.pdf", null, "EN")), "0123456789abcdef"));
    }

    private static MockHttpServletRequestBuilder json(String body) {
        return post(URL).contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8));
    }

    private static RequestPostProcessor from(String ip) {
        return request -> { request.setRemoteAddr(ip); return request; };
    }

  
    @Test void anonymous_user_gets_an_answer_without_any_token_or_csrf() throws Exception {
        mvc.perform(json("{\"query\":\"what is paracetamol used for\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(DATA + ".type").value("ANSWER"))
                .andExpect(jsonPath(DATA + ".language").value("EN"))
                .andExpect(jsonPath(DATA + ".sources[0].documentId").value(7))
                .andExpect(jsonPath(DATA + ".sources[0].score").doesNotExist());           // internal fields are never exposed
    }

    @Test void an_invalid_or_expired_token_does_not_break_the_public_endpoint() throws Exception {
        // If this fails, add shouldNotFilter(...) for /api/chatbot/query to your JWT filter (guide, Step 6).
        mvc.perform(json("{\"query\":\"hello there\"}").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isOk());
    }

    @Test void a_tamil_question_reaches_the_backend_intact_and_the_tamil_reply_is_utf8() throws Exception {
        String tamil = "பாராசிட்டமால் எதற்கு பயன்படுகிறது";
        when(backend.query(anyString())).thenReturn(new ChatBackendResult("ANSWER", "TA", "பாராசிட்டமால் வலியைக் குறைக்கும்.", List.of(), null));
        String body = mvc.perform(json("{\"query\":\"" + tamil + "\"}")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(backend).query(sent.capture());
        assertEquals(java.text.Normalizer.normalize(tamil, java.text.Normalizer.Form.NFC), sent.getValue());
        assertTrue(body.contains("பாராசிட்டமால் வலியைக் குறைக்கும்."), body);
    }

    @Test void hidden_characters_are_removed_before_the_query_leaves_spring() throws Exception {
        mvc.perform(json("{\"query\":\"  what\\u200b is\\u202e paracetamol\\u0000  \"}")).andExpect(status().isOk());
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(backend).query(sent.capture());
        assertEquals("what is paracetamol", sent.getValue());
    }

    @Test void a_chatbot_outage_is_a_200_fallback_in_the_query_language_not_an_error() throws Exception {
        when(backend.query(anyString())).thenThrow(new ChatbotUnavailableException("down"));
        mvc.perform(json("{\"query\":\"எனக்கு உதவி வேண்டும்\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(DATA + ".type").value("FALLBACK"))
                .andExpect(jsonPath(DATA + ".language").value("TA"));
    }

   
    @ParameterizedTest @ValueSource(strings = {"{}", "{\"query\":null}", "{\"query\":\"\"}", "{\"query\":\"   \"}", "{\"query\":5}", "{\"query\":true}",
            "{\"query\":[\"a\"]}", "{\"query\":{\"a\":1}}", "{\"query\":\"???\"}", "{\"query\":\"\\ud800\"}"})
    void non_text_empty_or_invalid_queries_are_rejected_with_400(String body) throws Exception {
        mvc.perform(json(body)).andExpect(status().isBadRequest());
        verify(backend, never()).query(anyString());                    // nothing reaches Django
    }

    @ParameterizedTest @ValueSource(strings = {"not json", "[]", "\"just a string\"", "{\"query\":"})
    void a_body_that_is_not_a_json_object_is_400(String body) throws Exception {
        mvc.perform(json(body)).andExpect(status().isBadRequest());
    }

    @Test void a_missing_body_is_400() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isBadRequest());
    }

    @Test void the_length_limit_is_500_characters() throws Exception {
        mvc.perform(json("{\"query\":\"" + "a".repeat(500) + "\"}")).andExpect(status().isOk());
        mvc.perform(json("{\"query\":\"" + "a".repeat(501) + "\"}")).andExpect(status().isBadRequest());
    }

    @Test void an_oversize_request_is_refused_by_its_size_with_400() throws Exception {
        mvc.perform(json("{\"query\":\"" + "a".repeat(9000) + "\"}")).andExpect(status().isBadRequest());
        verify(backend, never()).query(anyString());
    }

    @ParameterizedTest @ValueSource(strings = {"text/plain", "application/x-www-form-urlencoded", "application/xml", "multipart/form-data"})
    void only_json_is_accepted(String contentType) throws Exception {
        mvc.perform(post(URL).contentType(contentType).content("query=hello")).andExpect(status().isUnsupportedMediaType());
    }


    @Test void the_21st_request_in_a_minute_from_one_client_is_429_with_retry_after() throws Exception {
        for (int i = 1; i <= 20; i++) {
            mvc.perform(json("{\"query\":\"hello there\"}").with(from("203.0.113.5"))).andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Remaining", String.valueOf(20 - i)));
        }
        mvc.perform(json("{\"query\":\"hello there\"}").with(from("203.0.113.5")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test void another_client_is_not_affected_by_the_first_clients_limit() throws Exception {
        for (int i = 0; i < 21; i++) mvc.perform(json("{\"query\":\"hello there\"}").with(from("203.0.113.5")));
        mvc.perform(json("{\"query\":\"hello there\"}").with(from("203.0.113.6"))).andExpect(status().isOk());
    }

    @Test void a_spoofed_x_forwarded_for_header_cannot_dodge_the_limit() throws Exception {
        for (int i = 1; i <= 20; i++) {
            mvc.perform(json("{\"query\":\"hello there\"}").with(from("203.0.113.5")).header("X-Forwarded-For", "198.51.100." + i)).andExpect(status().isOk());
        }
        mvc.perform(json("{\"query\":\"hello there\"}").with(from("203.0.113.5")).header("X-Forwarded-For", "198.51.100.99"))
                .andExpect(status().isTooManyRequests());               // needs chatbot.api.trusted-proxies to be EMPTY in the test profile
    }

    @Test void invalid_requests_also_count_towards_the_limit() throws Exception {
        for (int i = 0; i < 20; i++) mvc.perform(json("{}").with(from("203.0.113.7"))).andExpect(status().isBadRequest());
        mvc.perform(json("{}").with(from("203.0.113.7"))).andExpect(status().isTooManyRequests());
    }
}
