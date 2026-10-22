
package com.onecare.backend.chatbot.knowledgebase;

import java.util.List;
import java.util.Map;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.onecare.backend.chatbot.config.ChatbotProperties;
import com.onecare.backend.chatbot.exception.ChatbotUnavailableException;

@Component
public class DjangoKnowledgeBaseClient implements KnowledgeBaseClient {

    private static final String PATH = "/internal/knowledge-base/links";

    private final RestTemplate rest;
    private final ChatbotProperties props;

    public DjangoKnowledgeBaseClient(
            RestTemplateBuilder builder,
            ChatbotProperties props) {

        this.props = props;

        this.rest = builder
                .rootUri(props.getBaseUrl())
                .setConnectTimeout(props.getConnectTimeout())
                .setReadTimeout(props.getReadTimeout())
                .build();
    }

    @Override
    public KnowledgeDocumentResponse submitLink(String url, Long actorId) {
        HttpHeaders headers = new HttpHeaders();

        headers.setBearerAuth(props.getServiceToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-OneCare-User-Id", actorId.toString());

        Map<String, String> body = Map.of("url", url);

        HttpEntity<Map<String, String>> request =
                new HttpEntity<>(body, headers);

        KnowledgeDocumentResponse response = rest.exchange(
                PATH,
                HttpMethod.POST,
                request,
                KnowledgeDocumentResponse.class
        ).getBody();

        if (response == null) {
            throw new ChatbotUnavailableException(
                    "Django returned an empty response."
            );
        }

        return response;
    }
}
