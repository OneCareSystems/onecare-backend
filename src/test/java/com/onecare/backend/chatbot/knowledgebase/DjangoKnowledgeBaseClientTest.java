package com.onecare.backend.chatbot.knowledgebase;

import static org.junit.jupiter.api.Assertions.*;

import com.onecare.backend.chatbot.config.ChatbotProperties;
import com.onecare.backend.chatbot.exception.ChatbotUnavailableException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.mock.web.MockMultipartFile;

class DjangoKnowledgeBaseClientTest {
    private HttpServer server;
    private DjangoKnowledgeBaseClient client;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> query = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> length = new AtomicReference<>();
    private final AtomicReference<String> actor = new AtomicReference<>();
    private final AtomicReference<String> credential = new AtomicReference<>();
    private static final String DOC = "{\"id\":1,\"status\":\"PENDING\",\"title\":\"Guide\",\"sourceType\":\"PDF\",\"storagePath\":\"PRIVATE\"}";

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/knowledge-base", exchange -> {
            method.set(exchange.getRequestMethod()); query.set(exchange.getRequestURI().getRawQuery());
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            body.set(new String(bytes, StandardCharsets.UTF_8));
            length.set(exchange.getRequestHeaders().getFirst("Content-Length"));
            actor.set(exchange.getRequestHeaders().getFirst("X-OneCare-User-Id"));
            credential.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String path = exchange.getRequestURI().getPath();
            int status = path.endsWith("/400") ? 400 : path.endsWith("/404") ? 404 : path.endsWith("/401") ? 401 : path.endsWith("/500") ? 500 : 200;
            if (path.endsWith("/999")) {
                try { Thread.sleep(150); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
            if (method.get().equals("DELETE") && status == 200) {
                exchange.sendResponseHeaders(204, -1); exchange.close(); return;
            }
            String payload = path.endsWith("/400") ? "" : status != 200 ? "{\"error\":{\"message\":\"Resource not found\"}}"
                    : path.equals("/internal/knowledge-base") && method.get().equals("GET")
                    ? "{\"items\":[" + DOC + "],\"count\":1,\"page\":1,\"pageSize\":20,\"totalPages\":1}" : DOC;
            byte[] response = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        client = create(Duration.ofSeconds(2));
    }

    private DjangoKnowledgeBaseClient create(Duration timeout) {
        return new DjangoKnowledgeBaseClient(new RestTemplateBuilder(), new ChatbotProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-service-token", Duration.ofSeconds(2), timeout));
    }

    @AfterEach void stop() { server.stop(0); }

    private void authenticationWasForwarded() {
        assertEquals("42", actor.get());
        assertEquals("Bearer test-service-token", credential.get());
    }

    @Test void forwards_link_json_with_length_and_server_identity() {
        assertEquals(1L, client.submitLink("https://who.int/page", 42L).id());
        assertEquals("POST", method.get());
        assertEquals("{\"url\":\"https://who.int/page\"}", body.get());
        assertEquals(String.valueOf(body.get().getBytes(StandardCharsets.UTF_8).length), length.get());
        authenticationWasForwarded();
    }

    @Test void forwards_pdf_as_length_delimited_multipart() {
        var file = new MockMultipartFile("file", "guide.pdf", "application/pdf", "%PDF-1.4 sample".getBytes(StandardCharsets.UTF_8));
        assertEquals("PENDING", client.uploadPdf(file, "Clinic guide", 42L).status());
        assertTrue(body.get().contains("filename=\"guide.pdf\""));
        assertTrue(body.get().contains("%PDF-1.4 sample"));
        assertTrue(body.get().contains("Clinic guide"));
        assertEquals(String.valueOf(body.get().getBytes(StandardCharsets.UTF_8).length), length.get());
        authenticationWasForwarded();
    }

    @Test void forwards_listing_filters_and_safe_details_and_removal() throws Exception {
        assertEquals(1, client.list(2, 1, "WEB", "INDEXED", 42L).count());
        assertEquals("page=2&pageSize=1&sourceType=WEB&status=INDEXED", query.get());
        authenticationWasForwarded();
        var document = client.get(1, 42L);
        assertEquals("Guide", document.title());
        assertFalse(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(document).contains("PRIVATE"));
        client.remove(1, 42L);
        assertEquals("DELETE", method.get());
    }

    @Test void preserves_not_found_but_hides_service_authentication_and_internal_errors() {
        assertEquals(400, assertThrows(KnowledgeBaseRequestException.class, () -> client.get(400, 42L)).status());
        assertEquals(404, assertThrows(KnowledgeBaseRequestException.class, () -> client.get(404, 42L)).status());
        assertThrows(ChatbotUnavailableException.class, () -> client.get(401, 42L));
        assertThrows(ChatbotUnavailableException.class, () -> client.get(500, 42L));
    }

    @Test void timeouts_and_connection_failures_are_service_unavailable() {
        assertThrows(ChatbotUnavailableException.class, () -> create(Duration.ofMillis(20)).get(999, 42L));
        server.stop(0);
        assertThrows(ChatbotUnavailableException.class, () -> client.get(1, 42L));
    }
}
