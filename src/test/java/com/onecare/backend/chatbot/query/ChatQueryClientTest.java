package com.onecare.backend.chatbot.query;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onecare.backend.chatbot.config.ChatbotProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

class ChatQueryClientTest {
    @Test void sends_utf8_json_with_content_length_for_django_wsgi() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> length = new AtomicReference<>();
        AtomicReference<String> transfer = new AtomicReference<>();
        AtomicReference<byte[]> received = new AtomicReference<>();
        server.createContext("/internal/chat/query", exchange -> {
            length.set(exchange.getRequestHeaders().getFirst("Content-Length"));
            transfer.set(exchange.getRequestHeaders().getFirst("Transfer-Encoding"));
            received.set(exchange.getRequestBody().readAllBytes());
            byte[] response = "{\"type\":\"CLINIC_FACT\",\"language\":\"EN\",\"message\":\"Clinic information\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ChatbotProperties props = new ChatbotProperties("http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-service-token", Duration.ofSeconds(2), Duration.ofSeconds(2));
            ChatQueryClient client = new ChatQueryClient(new RestTemplateBuilder(), props);
            String query = "கிளினிக் நேரம் என்ன? \"quoted\"";
            assertEquals("CLINIC_FACT", client.query(query).type());
            assertNull(transfer.get());
            assertEquals(String.valueOf(received.get().length), length.get());
            assertEquals(query, new ObjectMapper().readTree(received.get()).get("query").asText());
        } finally {
            server.stop(0);
        }
    }
}
