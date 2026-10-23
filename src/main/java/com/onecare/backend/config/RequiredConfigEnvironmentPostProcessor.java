package com.onecare.backend.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * Fail-fast startup validation for required configuration (DDP-#49, AC3).
 *
 * <p>Runs after config data (application-*.properties, env vars) is loaded and
 * before any bean is created. When a property listed in
 * {@code config.required-props} is missing, empty, or references an unset
 * placeholder (e.g. {@code jwt.secret=${JWT_SECRET}} with no JWT_SECRET in the
 * environment), startup fails with a message that names the property and its
 * environment-variable form — <b>never the value</b>.
 *
 * <p>{@code config.required-props} is declared per profile in
 * application-dev/staging/prod.properties. When the property is absent (local
 * default profile, unit tests) this processor is a no-op.
 *
 * <p>Example failure:
 * <pre>
 * Missing required configuration: jwt.secret (set the JWT_SECRET environment
 * variable, see onecare-infra/.env.example). Values are never logged.
 * </pre>
 */
public class RequiredConfigEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final String REQUIRED_PROPS_PROPERTY = "config.required-props";

    /** After ConfigDataEnvironmentPostProcessor so profile files are already loaded. */
    private static final int ORDER = ConfigDataEnvironmentPostProcessor.ORDER + 10;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String required = environment.getProperty(REQUIRED_PROPS_PROPERTY);
        if (required == null || required.isBlank()) {
            return;
        }

        List<String> missing = new ArrayList<>();
        for (String property : required.split(",")) {
            String name = property.trim();
            if (name.isEmpty()) {
                continue;
            }
            String hint = missingVariableHint(environment, name);
            if (hint != null) {
                missing.add(name + " (set the " + hint + " environment variable)");
            }
        }

        if (!missing.isEmpty()) {
            // Deliberately lists NAMES only — a value must never appear here (AC3).
            throw new IllegalStateException(
                    "Missing required configuration: " + String.join("; ", missing)
                            + ". See onecare-infra/.env.example. Values are never logged.");
        }
    }

    /**
     * Returns the environment-variable name the operator should set, or
     * {@code null} when the property is present and non-blank.
     *
     * <p>When a profile file declares {@code spring.datasource.url=${DB_URL}}
     * and DB_URL is unset, placeholder resolution fails and the exception
     * message names the referenced variable — that name ({@code DB_URL}) is
     * the most useful hint. Otherwise fall back to the relaxed-binding form
     * of the property itself ({@code SPRING_DATASOURCE_URL}).
     */
    private String missingVariableHint(Environment environment, String property) {
        String value;
        try {
            value = environment.getProperty(property);
        } catch (RuntimeException unresolvedPlaceholder) {
            // ${SOME_VAR} referenced in a profile file but SOME_VAR is not set:
            // Spring's message is "Could not resolve placeholder 'SOME_VAR' ...".
            return placeholderName(unresolvedPlaceholder.getMessage()).orElseGet(() -> toEnvVar(property));
        }
        return (value == null || value.isBlank()) ? toEnvVar(property) : null;
    }

    /** Extracts SOME_VAR from Spring's "Could not resolve placeholder 'SOME_VAR'" message. */
    private java.util.Optional<String> placeholderName(String message) {
        if (message == null) {
            return java.util.Optional.empty();
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("Could not resolve placeholder '([^']+)'")
                .matcher(message);
        return matcher.find() ? java.util.Optional.of(matcher.group(1)) : java.util.Optional.empty();
    }

    /** Relaxed binding in reverse: jwt.secret -> JWT_SECRET, db.url -> DB_URL. */
    private String toEnvVar(String property) {
        return property.replace('.', '_').toUpperCase();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
