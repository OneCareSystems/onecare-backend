package com.onecare.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AC3 (negative): a missing required env var must abort startup with a message
 * that NAMES the variable and never prints a value.
 */
class RequiredConfigEnvironmentPostProcessorTest {

    private final RequiredConfigEnvironmentPostProcessor processor =
            new RequiredConfigEnvironmentPostProcessor();

    private final SpringApplication application = new SpringApplication();

    @Test
    void missingPropertyFailsStartupNamingTheVariable() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(RequiredConfigEnvironmentPostProcessor.REQUIRED_PROPS_PROPERTY,
                "jwt.secret,spring.datasource.url");
        // Mirror the real profile files: the properties exist but point at
        // ${JWT_SECRET} / ${DB_URL}, neither of which is set here.
        environment.setProperty("jwt.secret", "${JWT_SECRET}");
        environment.setProperty("spring.datasource.url", "${DB_URL}");

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, application))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.secret")
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("spring.datasource.url")
                .hasMessageContaining("DB_URL")
                // AC3: the failure names variables, it never carries values.
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret-value"));
    }

    @Test
    void absentPropertyFallsBackToRelaxedBindingName() {
        // No property at all (not even a ${...} reference): the hint falls back
        // to the relaxed-binding env-var form of the property name.
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(RequiredConfigEnvironmentPostProcessor.REQUIRED_PROPS_PROPERTY,
                "spring.datasource.url");

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, application))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPRING_DATASOURCE_URL");
    }

    @Test
    void presentPropertiesPass() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(RequiredConfigEnvironmentPostProcessor.REQUIRED_PROPS_PROPERTY, "jwt.secret");
        environment.setProperty("jwt.secret", "some-base64-secret");

        processor.postProcessEnvironment(environment, application); // no exception
    }

    @Test
    void blankValueTreatedAsMissing() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(RequiredConfigEnvironmentPostProcessor.REQUIRED_PROPS_PROPERTY, "jwt.secret");
        environment.setProperty("jwt.secret", "   ");

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, application))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.secret");
    }

    @Test
    void unresolvedPlaceholderTreatedAsMissing() {
        // A profile file declares jwt.secret=${JWT_SECRET} but the env var is
        // absent: property resolution throws, which must count as missing.
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(RequiredConfigEnvironmentPostProcessor.REQUIRED_PROPS_PROPERTY, "jwt.secret");
        environment.setProperty("jwt.secret", "${JWT_SECRET_NOT_SET_ANYWHERE}");

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, application))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.secret")
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void messageNeverContainsAnyResolvedValue() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(RequiredConfigEnvironmentPostProcessor.REQUIRED_PROPS_PROPERTY,
                "jwt.secret,backup.encryption-key");
        environment.setProperty("jwt.secret", "s3cr3t-value-must-not-leak");
        // backup.encryption-key missing -> failure, and jwt.secret's value
        // must not appear in the message even though it IS resolvable.
        environment.setProperty("backup.encryption-key", "");

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, application))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("backup.encryption-key")
                .satisfies(e -> assertThat(e.getMessage())
                        .doesNotContain("s3cr3t-value-must-not-leak")
                        .doesNotContain("BACKUP_ENCRYPTION_KEY= "));
    }

    @Test
    void noOpWhenRequiredPropsNotConfigured() {
        // Local default profile and the test suite set no config.required-props.
        processor.postProcessEnvironment(new MockEnvironment(), application); // no exception
    }

    @Test
    void entriesAreTrimmedAndBlankSegmentsIgnored() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(RequiredConfigEnvironmentPostProcessor.REQUIRED_PROPS_PROPERTY,
                "  jwt.secret  , , backup.encryption-key ");
        environment.setProperty("jwt.secret", "present");
        environment.setProperty("backup.encryption-key", "present");

        processor.postProcessEnvironment(environment, application); // no exception
    }
}
