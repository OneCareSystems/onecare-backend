package com.onecare.backend.chatbot.knowledgebase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onecare.backend.chatbot.config.ChatbotProperties;
import com.onecare.backend.chatbot.exception.ChatbotUnavailableException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class DjangoKnowledgeBaseClient implements KnowledgeBaseClient {
    private static final String PATH = "/internal/knowledge-base";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final RestTemplate rest;
    private final ChatbotProperties props;

    public DjangoKnowledgeBaseClient(RestTemplateBuilder builder, ChatbotProperties props) {
        this.props = props;
        this.rest = builder.rootUri(props.baseUrl()).connectTimeout(props.connectTimeout())
                .readTimeout(props.readTimeout()).build();
    }

    private HttpHeaders headers(Long actorId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(props.serviceToken());
        headers.set("X-OneCare-User-Id", actorId.toString());
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    @Override
    public KnowledgeDocumentResponse submitLink(String url, Long actorId) {
        HttpHeaders headers = headers(actorId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        byte[] body = JSON.createObjectNode().put("url", url).toString().getBytes(StandardCharsets.UTF_8);
        headers.setContentLength(body.length); // Django WSGI requires a length rather than chunked JSON.
        return exchange(PATH + "/links", HttpMethod.POST, new HttpEntity<>(body, headers), KnowledgeDocumentResponse.class);
    }

    @Override
    public KnowledgeDocumentResponse uploadPdf(MultipartFile file, String title, Long actorId) {
        HttpHeaders headers = headers(actorId);
        try {
            var parts = new LinkedMultiValueMap<String, Object>();
            String original = file.getOriginalFilename();
            String name = original == null ? "document.pdf" : original.replace('\\', '/');
            final String filename = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\r\n]", "_");
            parts.add("file", new ByteArrayResource(file.getBytes()) {
                @Override public String getFilename() { return filename; }
            });
            parts.add("title", title == null ? "" : title);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            new FormHttpMessageConverter().write(parts, MediaType.MULTIPART_FORM_DATA, new HttpOutputMessage() {
                @Override public HttpHeaders getHeaders() { return headers; }
                @Override public java.io.OutputStream getBody() { return buffer; }
            });
            byte[] body = buffer.toByteArray();
            headers.setContentLength(body.length);
            return exchange(PATH, HttpMethod.POST, new HttpEntity<>(body, headers), KnowledgeDocumentResponse.class);
        } catch (IOException exception) {
            throw new ChatbotUnavailableException("The PDF could not be forwarded.", exception);
        }
    }

    @Override
    public KnowledgeDocumentPage list(int page, int pageSize, String sourceType, String status, Long actorId) {
        var uri = UriComponentsBuilder.fromPath(PATH).queryParam("page", page).queryParam("pageSize", pageSize);
        if (sourceType != null && !sourceType.isBlank()) uri.queryParam("sourceType", sourceType);
        if (status != null && !status.isBlank()) uri.queryParam("status", status);
        return exchange(uri.build().encode().toUriString(), HttpMethod.GET, new HttpEntity<>(headers(actorId)), KnowledgeDocumentPage.class);
    }

    @Override
    public KnowledgeDocumentResponse get(long id, Long actorId) {
        return exchange(PATH + "/" + id, HttpMethod.GET, new HttpEntity<>(headers(actorId)), KnowledgeDocumentResponse.class);
    }

    @Override
    public void remove(long id, Long actorId) {
        exchange(PATH + "/" + id, HttpMethod.DELETE, new HttpEntity<>(headers(actorId)), Void.class);
    }

    @Override
    public byte[] file(long id, Long actorId) {
        HttpHeaders headers = headers(actorId);
        headers.setAccept(List.of(MediaType.APPLICATION_PDF));
        return exchange(PATH + "/" + id + "/file", HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
    }

    private <T> T exchange(String path, HttpMethod method, HttpEntity<?> entity, Class<T> type) {
        try {
            T body = rest.exchange(path, method, entity, type).getBody();
            if (body == null && type != Void.class) throw new ChatbotUnavailableException("Knowledge base returned an empty response.");
            return body;
        } catch (HttpStatusCodeException exception) {
            int status = exception.getStatusCode().value();
            if (Set.of(400, 404, 409, 413, 415, 422).contains(status)) {
                String message = "Knowledge base request rejected.";
                try {
                    var error = JSON.readTree(exception.getResponseBodyAsByteArray());
                    String detail = error == null ? "" : error.path("error").path("message").asText();
                    if (!detail.isBlank()) message = detail.substring(0, Math.min(detail.length(), 500));
                } catch (IOException ignored) { /* Never forward HTML or raw service errors. */ }
                throw new KnowledgeBaseRequestException(status, message);
            }
            throw new ChatbotUnavailableException("Knowledge base service is unavailable.");
        } catch (RestClientException exception) {
            throw new ChatbotUnavailableException("Knowledge base service is unavailable or timed out.", exception);
        }
    }
}
