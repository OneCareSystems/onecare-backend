package com.onecare.backend.chatbot.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for Stage 5 (API-01 to API-03).
 * Lists are comma-separated strings, so they are easy to set from environment variables.
 */
@ConfigurationProperties(prefix = "chatbot.api")
public class ChatApiProperties {

    private final int maxQueryChars;
    private final int maxBodyBytes;
    private final int rateLimitPerMinute;
    private final int maxTrackedClients;
    private final String trustedProxies;
    private final String urlAllowlist;

    public ChatApiProperties(
            @DefaultValue("500") int maxQueryChars,
            @DefaultValue("8192") int maxBodyBytes,
            @DefaultValue("20") int rateLimitPerMinute,
            @DefaultValue("10000") int maxTrackedClients,
            String trustedProxies,
            String urlAllowlist) {

        this.maxQueryChars = maxQueryChars;
        this.maxBodyBytes = maxBodyBytes;
        this.rateLimitPerMinute = rateLimitPerMinute;
        this.maxTrackedClients = maxTrackedClients;
        this.trustedProxies = trustedProxies;
        this.urlAllowlist = urlAllowlist;
    }

    public int getMaxQueryChars() {
        return maxQueryChars;
    }

    public int getMaxBodyBytes() {
        return maxBodyBytes;
    }

    public int getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public int getMaxTrackedClients() {
        return maxTrackedClients;
    }

    public String getTrustedProxies() {
        return trustedProxies;
    }

    public String getUrlAllowlist() {
        return urlAllowlist;
    }

    public List<String> trustedProxyList() {
        return split(trustedProxies);
    }

    public List<String> urlAllowlistDomains() {
        return split(urlAllowlist);
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }

        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
