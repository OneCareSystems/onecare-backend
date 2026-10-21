package com.onecare.backend.chatbot;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * Spring Boot to Django: POST /internal/chat/query. Authenticated ONLY with the service token (chatbot.service-token).
 * The patient's own request headers are never forwarded. Patients are anonymous, so no X-OneCare-User-Id is sent.
 * Every failure becomes ChatbotUnavailableException. Nothing here logs the query text or the token.
 */
@Component
public class ChatQueryClient implements ChatBackend {
    private static final Logger log = LoggerFactory.getLogger(ChatQueryClient.class);
    private static final String PATH = "/internal/chat/query";

    private final RestTemplate rest;
    private final ChatbotProperties props;

    public ChatQueryClient(RestTemplateBuilder builder, ChatbotProperties props) {
        this.props = props;
        this.rest = builder.rootUri(props.baseUrl())
                .connectTimeout(props.connectTimeout()).readTimeout(props.readTimeout()).build();
    }

    @Override
    public ChatBackendResult query(String text) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(props.serviceToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        try {
            Map<?, ?> body = rest.exchange(PATH, HttpMethod.POST, new HttpEntity<>(Map.of("query", text), headers), Map.class).getBody();
            return ChatBackendResult.fromMap(body);
        } catch (HttpStatusCodeException ex) {
            // 401/403 = wrong service token, 400 = limits differ from Django's, 5xx = Django or its LLM is down
            log.error("Chatbot service returned HTTP {} for a chat query", ex.getStatusCode().value());
            throw new ChatbotUnavailableException("Chatbot service error (HTTP " + ex.getStatusCode().value() + ")");
        } catch (ResourceAccessException ex) {
            log.error("Chatbot service unreachable or timed out ({})", ex.getClass().getSimpleName());
            throw new ChatbotUnavailableException("Chatbot service is unreachable", ex);
        }
    }
}
