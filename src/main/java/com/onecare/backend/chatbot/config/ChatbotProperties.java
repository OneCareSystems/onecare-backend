package com.onecare.backend.chatbot.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;


import jakarta.validation.constraints.NotBlank;

/** Connection settings for the internal Django chatbot service. */
@ConfigurationProperties(prefix = "chatbot")
public record ChatbotProperties(
       @NotBlank  String baseUrl,
       @NotBlank String serviceToken,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("60s") Duration readTimeout) {
}
