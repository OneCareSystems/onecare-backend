package com.onecare.backend.ci;

import com.puppycrawl.tools.checkstyle.Checker;
import com.puppycrawl.tools.checkstyle.ConfigurationLoader;
import com.puppycrawl.tools.checkstyle.api.AuditEvent;
import com.puppycrawl.tools.checkstyle.api.AuditListener;
import com.puppycrawl.tools.checkstyle.api.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Negative test for AC2: an injected lint violation must be detected, so the
 * `lint` check fails and the pull request is blocked.
 *
 * <p>The test runs the same Checkstyle engine and the same
 * {@code config/checkstyle/checkstyle.xml} ruleset that the CI `lint` stage
 * executes ({@code mvn checkstyle:check}), against an in-memory source file
 * that contains a deliberate violation.</p>
 */
class LintGateNegativeTest {

    private static final Path CHECKSTYLE_CONFIG = Paths.get("config", "checkstyle", "checkstyle.xml");

    @TempDir
    Path tempDir;

    /** Collects the violations Checkstyle reports for one file. */
    private static final class Collector implements AuditListener {
        private final List<AuditEvent> errors = new ArrayList<>();

        @Override
        public void auditStarted(AuditEvent event) {
            // no-op
        }

        @Override
        public void auditFinished(AuditEvent event) {
            // no-op
        }

        @Override
        public void fileStarted(AuditEvent event) {
            // no-op
        }

        @Override
        public void fileFinished(AuditEvent event) {
            // no-op
        }

        @Override
        public void addError(AuditEvent event) {
            errors.add(event);
        }

        @Override
        public void addException(AuditEvent event, Throwable throwable) {
            errors.add(event); // a thrown error also blocks the lint stage
        }

        List<AuditEvent> errors() {
            return errors;
        }
    }

    private List<AuditEvent> audit(String fileName, String source) throws Exception {
        assertTrue(Files.exists(CHECKSTYLE_CONFIG), "Checkstyle ruleset must exist");
        Configuration configuration = ConfigurationLoader.loadConfiguration(
                CHECKSTYLE_CONFIG.toString(),
                key -> key, // the project ruleset defines no external properties
                ConfigurationLoader.IgnoredModulesOptions.EXECUTE);

        Path file = tempDir.resolve(fileName);
        Files.writeString(file, source);

        Collector collector = new Collector();
        Checker checker = new Checker();
        checker.setModuleClassLoader(Checker.class.getClassLoader());
        checker.configure(configuration);
        checker.addListener(collector);
        try {
            checker.process(List.of(file.toFile()));
        } finally {
            checker.destroy();
        }
        return collector.errors();
    }

    @Test
    @DisplayName("AC2 negative: an injected lint violation is detected -> the lint check fails")
    void injectedLintViolation_isDetected() throws Exception {
        String violatingSource = """
                package com.onecare.example;

                import java.util.List;

                public class InjectedLintViolation {

                    public String greeting() {
                        return "hello";
                    }
                }
                """;

        List<AuditEvent> violations = audit("InjectedLintViolation.java", violatingSource);

        assertFalse(violations.isEmpty(),
                "the unused import must be reported, otherwise the lint stage could not block a PR");
        assertTrue(violations.stream().anyMatch(event -> event.getFileName().contains("InjectedLintViolation")),
                "the violation must point at the injected file so the failing stage names it");
    }

    @Test
    @DisplayName("AC1: clean source passes the same gate that CI runs")
    void cleanSource_passesTheGate() throws Exception {
        String cleanSource = """
                package com.onecare.example;

                public class CleanExample {

                    public String greeting() {
                        return "hello";
                    }
                }
                """;

        assertTrue(audit("CleanExample.java", cleanSource).isEmpty(),
                "clean source must produce zero violations");
    }

    @Test
    @DisplayName("AC2: the Maven lint wiring fails the build on error-level violations")
    void mavenLintWiringFailsTheBuildOnErrorViolations() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        assertTrue(pom.contains("<artifactId>maven-checkstyle-plugin</artifactId>"),
                "Checkstyle must be wired into the build");
        assertTrue(pom.contains("<configLocation>config/checkstyle/checkstyle.xml</configLocation>"),
                "the project ruleset must be the one CI runs");
        assertTrue(pom.contains("<failOnViolation>true</failOnViolation>"),
                "violations must fail the build");
        assertTrue(pom.contains("<violationSeverity>error</violationSeverity>"),
                "error-severity violations must fail the build");
        assertTrue(pom.contains("<artifactId>checkstyle</artifactId>"),
                "the Checkstyle engine must be pinned so tests and CI use the same version");
    }
}
