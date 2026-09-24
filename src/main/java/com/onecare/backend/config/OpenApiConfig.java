package com.onecare.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

@Configuration
public class OpenApiConfig {

    /**
     * Endpoints reachable without an Authorization header (SecurityConfig
     * permitAll) — a 401 does not apply to them.
     */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/auth/forgot-password",
            "/api/auth/reset-password");

    /**
     * Prefixes guarded by hasAnyRole rules in SecurityConfig — the only
     * operations that can produce AccessDeniedException (403). Keep in sync
     * when adding a role-restricted route.
     */
    private static final List<String> RBAC_PATH_PREFIXES = List.of(
            "/api/users",
            "/api/patients",
            "/api/medicines",
            "/api/prescriptions",
            "/api/appointments",
            "/api/audit");

    @Bean
    public OpenAPI oneCareOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("OneCare Clinic Management API")
                        .version("1.0.0")
                        .description(
                                "REST API for the OneCare Clinic Integrated Pharmacy Management System."))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "bearerAuth",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")));
    }

    /**
     * Centralizes the repetitive response documentation so controllers only
     * declare endpoint-specific codes:
     * - 500 on every operation (GlobalExceptionHandler catch-all)
     * - 400 on every operation that accepts a request body (@Valid)
     * - 401 on every operation except permitAll paths
     * - 403 on role-protected operations (SecurityConfig hasAnyRole)
     * - 429 on /api/auth/* (ApiRateLimitFilter scope)
     */
    @Bean
    public GlobalOpenApiCustomizer defaultResponsesCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, pathItem) ->
                    pathItem.readOperationsMap().forEach((httpMethod, operation) -> {
                        var responses = operation.getResponses();
                        if (responses == null) {
                            return;
                        }
                        if (operation.getRequestBody() != null) {
                            responses.putIfAbsent("400",
                                    new ApiResponse().description("Validation failed or malformed request"));
                        }
                        responses.putIfAbsent("500",
                                new ApiResponse().description("Internal Server Error"));
                        if (!PUBLIC_PATHS.contains(path)) {
                            responses.putIfAbsent("401",
                                    new ApiResponse().description("Missing or invalid access token"));
                        }
                        if (RBAC_PATH_PREFIXES.stream().anyMatch(path::startsWith)) {
                            responses.putIfAbsent("403",
                                    new ApiResponse().description("Forbidden: insufficient permissions"));
                        }
                        if (path.startsWith("/api/auth/")) {
                            responses.putIfAbsent("429",
                                    new ApiResponse().description("Too many requests"));
                        }
                    }));
        };
    }
}
