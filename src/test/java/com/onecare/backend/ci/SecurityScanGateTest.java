package com.onecare.backend.ci;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the security-scan stage: the scan must be able to fail
 * the build, its threshold must be real, and the secret scanner cannot be
 * neutralised for the workflow files themselves.
 */
class SecurityScanGateTest {

    private static final Path WORKFLOW = Path.of(".github", "workflows", "ci.yml");
    private static final Path GITLEAKS_CONFIG = Path.of(".gitleaks.toml");

    private static final Pattern CVSS_THRESHOLD = Pattern.compile("-DfailBuildOnCVSS=(\\d+)");

    /** OWASP Dependency-Check semantics: findings with CVSS >= threshold fail the build (11 disables). */
    private static boolean scanFailsBuild(double cvss, int threshold) {
        return cvss >= threshold;
    }

    private static int thresholdFromWorkflow() throws IOException {
        Matcher matcher = CVSS_THRESHOLD.matcher(Files.readString(WORKFLOW));
        assertTrue(matcher.find(), "the security scan must declare -DfailBuildOnCVSS");
        return Integer.parseInt(matcher.group(1));
    }

    @Test
    @DisplayName("AC2: the dependency scan fails on high/critical findings (threshold is real, not disabled)")
    void dependencyCheckThresholdFailsOnHighFindings() throws IOException {
        int threshold = thresholdFromWorkflow();
        assertEquals(7, threshold, "NFRPO-002: the scan must fail on CVSS >= 7 (high)");
        assertTrue(threshold <= 10, "a threshold above 10 would disable the gate");
        assertFalse(scanFailsBuild(5.0, threshold), "medium findings must not block the merge");
        assertTrue(scanFailsBuild(7.0, threshold), "high findings must block the merge");
        assertTrue(scanFailsBuild(9.8, threshold), "critical findings must block the merge");
    }

    @Test
    @DisplayName("AC2 negative: an injected plaintext credential is reported by the guard")
    void injectedPlaintextCredential_isReportedByTheGuard() {
        // The credential literals are assembled at runtime so this test file
        // itself never contains a plaintext credential - the CI secret scan
        // (gitleaks dir .) scans this file too.
        String passwordValue = "hunter2" + "notASecret";
        String tokenValue = "d3ploy" + "-t0ken-plaintext";
        String injectedWorkflow = String.join("\n",
                "jobs:",
                "  deploy:",
                "    steps:",
                "      - name: connect to the database",
                "        env:",
                "          DB_PASSWORD: " + passwordValue,
                "          DEPLOY_TOKEN: " + tokenValue);

        List<String> violations = WorkflowSecretScanner.findCredentialViolations(injectedWorkflow);

        assertFalse(violations.isEmpty(),
                "an injected plaintext credential must be reported so the check fails and blocks the PR");
        assertTrue(violations.stream().anyMatch(v -> v.contains("DB_PASSWORD")),
                "the violation must name the injected credential key");
    }

    @Test
    @DisplayName("AC2: secret scanning cannot be silenced or bypassed in the security-scan stage")
    void secretScanningCannotBeSilenced() throws IOException {
        String workflow = Files.readString(WORKFLOW);
        assertTrue(workflow.contains("gitleaks"), "the security scan must run secret scanning");
        assertFalse(workflow.contains("--exit-code 0"),
                "gitleaks must keep its non-zero exit code when secrets are found");
        assertFalse(workflow.contains("continue-on-error"),
                "the security scan must never fail open");
        assertTrue(workflow.contains("${{ secrets.NVD_API_KEY }}"),
                "the optional NVD API key must come from GitHub Secrets (AC3)");
    }

    @Test
    @DisplayName("AC3: the gitleaks allowlist must not cover the workflow files")
    void gitleaksAllowlistCannotNeutraliseWorkflowScanning() throws IOException {
        assertTrue(Files.exists(GITLEAKS_CONFIG), ".gitleaks.toml must exist");
        String config = Files.readString(GITLEAKS_CONFIG);
        assertFalse(config.contains(".github"),
                ".gitleaks.toml must not allowlist .github - workflow files are always scanned (AC3)");
        assertFalse(config.matches("(?s).*regexes\\s*=\\s*\\[\\s*'''.*'''\\s*\\].*"),
                "the allowlist must not swallow every finding with an empty/catch-all regex");
        assertTrue(config.contains("useDefault = true"),
                "the built-in gitleaks rules must stay enabled");
    }

    @Test
    @DisplayName("AC1: dependency review runs on pull requests and the OWASP report is published")
    void securityScanStageWiring() throws IOException {
        String workflow = Files.readString(WORKFLOW);
        assertTrue(workflow.contains("actions/dependency-review-action"),
                "dependency review (GitHub Advisory DB) must run on PRs");
        assertTrue(workflow.contains("fail-on-severity: high"),
                "dependency review must fail on high severity");
        assertTrue(workflow.contains("dependency-check-report"),
                "the OWASP report must be uploaded so findings are inspectable");
    }
}
