package com.onecare.backend.chatbot.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings for Stage 5 (API-01 to API-03). Lists are comma-separated strings, so they are easy to set from environment variables. */
@ConfigurationProperties(prefix = "chatbot.api")
public record ChatApiProperties(
        @DefaultValue("500") int maxQueryChars,            // keep equal to Django CHAT_MAX_QUERY_CHARS
        @DefaultValue("8192") int maxBodyBytes,            // a 500-character Tamil query is about 1.5 KB as UTF-8
        @DefaultValue("20") int rateLimitPerMinute,        // SDS public-endpoint policy: 20 requests/min/IP
        @DefaultValue("10000") int maxTrackedClients,
        @DefaultValue("") String trustedProxies,           // e.g. "127.0.0.1,10.0.0.0/8". Empty = never trust X-Forwarded-For
        @DefaultValue("") String urlAllowlist) {           // approved domains; use the SAME value as Django KB_URL_ALLOWLIST

    public List<String> trustedProxyList() { return split(trustedProxies); }

    public List<String> urlAllowlistDomains() { return split(urlAllowlist); }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();


import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Settings for Stage 5 (API-01 to API-03).
 * Lists are comma-separated strings, so they are easy to set from environment variables.
 */
@Component
public class ChatApiProperties {

    private final int maxQueryChars;
    private final int maxBodyBytes;
    private final int rateLimitPerMinute;
    private final int maxTrackedClients;
    private final String trustedProxies;
    private final String urlAllowlist;

    public ChatApiProperties(
            @Value("${chatbot.api.max-query-chars:500}") int maxQueryChars,
            @Value("${chatbot.api.max-body-bytes:8192}") int maxBodyBytes,
            @Value("${chatbot.api.rate-limit-per-minute:20}") int rateLimitPerMinute,
            @Value("${chatbot.api.max-tracked-clients:10000}") int maxTrackedClients,
            @Value("${chatbot.api.trusted-proxies:}") String trustedProxies,
            @Value("${chatbot.api.url-allowlist:}") String urlAllowlist) {

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
