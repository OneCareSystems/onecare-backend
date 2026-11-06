package com.onecare.backend.chatbot.config;

import java.time.Duration;

import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/** Connection settings for the internal Django chatbot service. */
@Validated
@ConfigurationProperties(prefix = "chatbot")
public class ChatbotProperties {

    @NotBlank
    private final String baseUrl;

    @NotBlank
    private final String serviceToken;

    private final Duration connectTimeout;
    private final Duration readTimeout;

    public ChatbotProperties(
            String baseUrl,
            String serviceToken,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("60s") Duration readTimeout) {
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
