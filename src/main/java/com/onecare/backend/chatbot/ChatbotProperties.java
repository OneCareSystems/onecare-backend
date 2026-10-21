package com.onecare.backend.chatbot;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Connection settings for the internal Django chatbot service. */
@ConfigurationProperties(prefix = "chatbot")
public record ChatbotProperties(
        @DefaultValue("http://localhost:8000") String baseUrl,
        @DefaultValue("") String serviceToken,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("60s") Duration readTimeout) {
}
