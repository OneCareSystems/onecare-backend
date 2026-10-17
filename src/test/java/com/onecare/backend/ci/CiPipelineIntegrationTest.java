package com.onecare.backend.ci;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onecare.backend.enums.Role;
import com.onecare.backend.security.RolePermission;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * end-to-end pipeline integration test.
 *
 * <p>Runs against the real Spring context - controller -&gt; service -&gt; repository on the
 * test database - to prove the application the CI pipeline builds actually works, and that
 * the OpenAPI document produced by that build carries none of
 * the secrets the security-scan job exists to protect.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CiPipelineIntegrationTest {

    private static final String PATIENT_ENDPOINT = "/api/patients";

    // Same shape PatientControllerIntegrationTest uses: full_name, dateOfBirth and
    // contactNo are required and gender is NOT NULL in the schema.
    private static final String VALID_BODY =
            """
            {"full_name":"Ci Pipeline","dateOfBirth":"1991-04-12",
             "contactNo":"0771111999","gender":"FEMALE"}""";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private Environment environment;

    /** Caller whose authorities come from the real RolePermission mapping (see SecurityConfig). */
    private RequestPostProcessor as(Role role) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
        RolePermission.getPermissions(role).stream()
                .map(SimpleGrantedAuthority::new)
                .forEach(authorities::add);
        return user(role.name().toLowerCase()).authorities(authorities);
    }

    @Test
    @DisplayName("controller -> service -> repository round trip through the real stack")
    void patientFlowWorksWithoutManualRepositoryAccess() throws Exception {
        // Create through the HTTP controller (drives service + repository).
        MvcResult created = mockMvc.perform(post(PATIENT_ENDPOINT)
                        .with(as(Role.ADMIN))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.patientId").isNumber())
                .andReturn();

        JsonNode data = objectMapper.readTree(created.getResponse().getContentAsString()).get("data");
        long id = data.get("patientId").asLong();
        assertTrue(id > 0, "service layer must persist the entity and return its generated id");

        // Read it back through controller -> service -> repository.
        MvcResult read = mockMvc.perform(get(PATIENT_ENDPOINT + "/" + id).with(as(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName").value("Ci Pipeline"))
                .andReturn();

        JsonNode fetched = objectMapper.readTree(read.getResponse().getContentAsString()).get("data");
        assertTrue(
                fetched.get("fullName").asText().equals("Ci Pipeline")
                        && fetched.get("patientId").asLong() == id,
                "service layer must persist and return the entity read from the repository");

        // Repository-backed list endpoint sees the created record.
        MvcResult list = mockMvc.perform(get(PATIENT_ENDPOINT)
                        .param("search", "Ci Pipeline")
                        .with(as(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andReturn();

        JsonNode rows = objectMapper.readTree(list.getResponse().getContentAsString()).get("data");
        assertTrue(rows.size() >= 1, "the created patient must be visible through the repository-backed list");

        // Rollback (class-level @Transactional) restores the database for every other test.
    }

    @Test
    @DisplayName("the CI test job supplies every credential from GitHub Secrets, never inline")
    void testEnvironmentSuppliesSecretsViaSecretsContextOnly() throws Exception {
        String workflow = Files.readString(Path.of(".github", "workflows", "ci.yml"), StandardCharsets.UTF_8);

        assertTrue(
                workflow.contains("JWT_SECRET"),
                "workflow must supply JWT_SECRET to the test job");
        assertTrue(
                WorkflowSecretScanner.containsSecretsContext(workflow),
                "workflow must read credentials from the secrets context (AC3)");
        assertFalse(
                workflow.matches("(?s).*JWT_SECRET:\\s+[^$\\s{].*"),
                "JWT_SECRET must never be given an inline literal value");
    }

    @Test
    @DisplayName("the build produces an OpenAPI document as an artifact")
    void openApiDocumentIsWrittenAsBuildArtifact() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        JsonNode docs = objectMapper.readTree(json);
        assertTrue(docs.has("openapi") || docs.has("paths"), "springdoc must publish the OpenAPI description");
        assertTrue(docs.get("paths").size() > 0, "OpenAPI document must describe at least one endpoint");

        Path out = Path.of("target", "openapi", "openapi.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, json, StandardCharsets.UTF_8);
        assertTrue(Files.size(out) > 0, "OpenAPI artifact must be written for the test job to upload");
    }

    @Test
    @DisplayName("the OpenAPI artifact must not leak configured secrets")
    void openApiArtifactContainsNoResolvedSecrets() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        String json = result.getResponse().getContentAsString();

        List<String> sensitiveKeys = List.of(
                "jwt.secret",
                "spring.datasource.url",
                "spring.datasource.password",
                "spring.mail.password",
                "spring.mail.username");
        for (String key : sensitiveKeys) {
            String value = environment.getProperty(key);
            if (value != null && value.length() >= 8) {
                assertFalse(
                        json.contains(value),
                        "OpenAPI artifact must not embed the resolved value of '" + key + "'");
            }
        }
        assertFalse(
                json.toLowerCase(Locale.ROOT).contains("local-test-jwt-signing-key"),
                "OpenAPI artifact must not embed the JWT signing key");
        assertFalse(
                json.toLowerCase(Locale.ROOT).matches("(?s).*\"password\"\\s*:\\s*\"[^\"$]+\".*"),
                "OpenAPI artifact must not contain inline password fields");
    }
}
