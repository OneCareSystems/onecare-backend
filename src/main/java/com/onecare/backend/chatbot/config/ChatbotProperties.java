package com.onecare.backend.chatbot.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.validation.constraints.NotBlank;

/** Connection settings for the internal Django chatbot service. */
@Component
public class ChatbotProperties {

    @NotBlank
    private final String baseUrl;

    @NotBlank
    private final String serviceToken;

    private final Duration connectTimeout;
    private final Duration readTimeout;

    public ChatbotProperties(
            @Value("${chatbot.base-url:http://localhost:8000}") String baseUrl,
            @Value("${chatbot.service-token}") String serviceToken,
            @Value("${chatbot.connect-timeout:5s}") Duration connectTimeout,
            @Value("${chatbot.read-timeout:60s}") Duration readTimeout) {
        this.baseUrl = baseUrl;
        this.serviceToken = serviceToken;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getServiceToken() {
        return serviceToken;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }
}