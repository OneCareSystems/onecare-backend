package com.onecare.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * AC3 (integration): the staging profile (PR 27) must still boot cleanly with
 * the new DDP-#49 configuration wired in, and a missing required variable must
 * abort startup with a message naming the variable — never its value.
 *
 * <p>The context is booted exactly as staging is wired in production: every
 * value arrives through the placeholder path ({@code ${DB_URL}},
 * {@code ${JWT_SECRET}}, ...) supplied as command-line arguments, which carry
 * the same precedence as environment variables in a container.
 */
class StagingProfileConfigIntegrationTest {

    /** Well-known base64 test secret (48 bytes) — same one the test suite uses. */
    private static final String TEST_JWT_SECRET =
            "bXktdGVzdC1zZWNyZXQtbXktdGVzdC1zZWNyZXQtbXktdGVzdC1zZWNyZXQ";
    /** Base64 of the 32 ASCII bytes "0123456789abcdef0123456789abcdef". */
    private static final String TEST_BACKUP_KEY =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private static String[] stagingArgs() {
        return new String[] {
                // Leading "--" turns each entry into a Spring property source
                // with command-line precedence, exactly like env vars in a
                // container. H2 stands in for the staging MySQL; every other
                // value flows through the same placeholders staging uses.
                "--DB_URL=jdbc:h2:mem:staging-profile-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "--DB_USERNAME=sa",
                "--DB_PASSWORD=staging-test-password",
                "--JWT_SECRET=" + TEST_JWT_SECRET,
                "--BACKUP_ENCRYPTION_KEY=" + TEST_BACKUP_KEY,
                "--FRONTEND_ALLOWED_ORIGINS=https://staging.example.com",
                "--CHATBOT_BASE_URL=http://chatbot:8000",
                // chatbot.service-token has no default in staging (required
                // placeholder, same class of variable as JWT_SECRET).
                "--CHATBOT_SERVICE_TOKEN=test-chatbot-service-token",
                "--FRONTEND_URL=https://staging.example.com",
                // Staging hardcodes the MySQL driver and validates the schema;
                // this throwaway H2 needs its own driver and no pre-existing
                // schema, so both are overridden at the highest precedence.
                "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.jpa.hibernate.ddl-auto=update",
                "--spring.sql.init.mode=never",
                // No Redis is running for this boot test.
                "--management.health.redis.enabled=false",
        };
    }

    /** Boots the real application with the staging profile, H2 replacing MySQL. */
    private static SpringApplicationBuilder stagingBuilder() {
        return new SpringApplicationBuilder(com.onecare.backend.BackendApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("staging")
                .properties("spring.main.banner-mode=off");
        // args are passed to run(...) so they carry command-line precedence,
        // matching how the container injects environment variables.
    }

    @Test
    void stagingProfileBootsCleanlyWithNewConfiguration() {
        try (ConfigurableApplicationContext context = stagingBuilder().run(stagingArgs())) {
            assertThat(context.getEnvironment().getActiveProfiles()).contains("staging");

            // The new keys from this ticket are actually resolvable in staging.
            assertThat(context.getEnvironment().getProperty("jwt.secret")).isEqualTo(TEST_JWT_SECRET);
            assertThat(context.getEnvironment().getProperty("backup.encryption-key"))
                    .isEqualTo(TEST_BACKUP_KEY);
            assertThat(context.getEnvironment().getProperty("encryption.column-key")).isEmpty();
            assertThat(context.getEnvironment().getProperty("management.endpoints.web.exposure.include"))
                    .isEqualTo("health");
            assertThat(context.getEnvironment().getProperty("config.required-props")).isNotBlank();
        }
    }

    @Test
    void missingJwtSecretFailsStartupNamingTheVariableNotTheValue() {
        // The fail-fast check must observe the variable as genuinely absent;
        // skip on machines where the developer really has JWT_SECRET exported.
        assumeTrue(System.getenv("JWT_SECRET") == null,
                "JWT_SECRET is set in this environment; cannot simulate it being missing");

        String[] args = Arrays.stream(stagingArgs())
                .filter(arg -> !arg.startsWith("--JWT_SECRET="))
                .toArray(String[]::new);

        assertThatThrownBy(() -> stagingBuilder().run(args))
                // The EnvironmentPostProcessor aborts before SpringApplication
                // can wrap the failure, so the validator's exception surfaces
                // directly from run().
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.secret")
                .hasMessageContaining("JWT_SECRET")
                // AC3: the variable is named; no value may leak into the log.
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(TEST_JWT_SECRET));
    }

    @Test
    void missingBackupEncryptionKeyFailsStartupNamingTheVariableNotTheValue() {
        assumeTrue(System.getenv("BACKUP_ENCRYPTION_KEY") == null,
                "BACKUP_ENCRYPTION_KEY is set in this environment; cannot simulate it being missing");

        String[] args = Arrays.stream(stagingArgs())
                .filter(arg -> !arg.startsWith("--BACKUP_ENCRYPTION_KEY="))
                .toArray(String[]::new);

        assertThatThrownBy(() -> stagingBuilder().run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("backup.encryption-key")
                .hasMessageContaining("BACKUP_ENCRYPTION_KEY")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(TEST_BACKUP_KEY));
    }
}
