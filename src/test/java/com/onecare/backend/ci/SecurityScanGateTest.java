package com.onecare.backend.ci;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the security-scan stage: the scan must be able to fail
 * the build, its threshold must be real, the secret scanner cannot be
 * neutralised for the workflow files themselves, and the NVD-key policy
 * must be branch-aware (main strict, develop lenient).
 */
class SecurityScanGateTest {

    private static final Path WORKFLOW = Path.of(".github", "workflows", "ci.yml");
    private static final Path GITLEAKS_CONFIG = Path.of(".gitleaks.toml");

    /**
     * Anchored to the start of a line so a commented-out or disabled
     * threshold elsewhere in the file cannot shadow the real one.
     */
    private static final Pattern CVSS_THRESHOLD =
            Pattern.compile("(?m)^\\s*-DfailBuildOnCVSS=(\\d+)\\s*\\\\?\\s*$");

    /** OWASP Dependency-Check semantics: findings with CVSS >= threshold fail the build (11 disables). */
    private static boolean scanFailsBuild(double cvss, int threshold) {
        return cvss >= threshold;
    }

    private static int thresholdFromWorkflow() throws IOException {
        Matcher matcher = CVSS_THRESHOLD.matcher(Files.readString(WORKFLOW));
        assertTrue(matcher.find(), "the security scan must declare -DfailBuildOnCVSS");
        int threshold = Integer.parseInt(matcher.group(1));
        // Defensive: if the regex ever matches more than once, the last
        // occurrence must not silently disable the gate.
        while (matcher.find()) {
            assertEquals(threshold, Integer.parseInt(matcher.group(1)),
                    "the workflow must declare exactly one -DfailBuildOnCVSS value");
        }
        return threshold;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> securityScanJob() throws IOException {
        Map<String, Object> workflow = new Yaml().load(Files.readString(WORKFLOW));
        Map<String, Object> jobs = (Map<String, Object>) workflow.get("jobs");
        assertNotNull(jobs, "the workflow must define jobs");
        Map<String, Object> job = (Map<String, Object>) jobs.get("security-scan");
        assertNotNull(job, "the workflow must define the security-scan job");
        return job;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stepNamed(Map<String, Object> job, String nameSubstring) {
        List<Map<String, Object>> steps = (List<Map<String, Object>>) job.get("steps");
        assertNotNull(steps, "the security-scan job must declare steps");
        for (Map<String, Object> step : steps) {
            Object name = step.get("name");
            if (name != null && String.valueOf(name).contains(nameSubstring)) {
                return step;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // AC2 - the dependency scan can fail the build
    // ------------------------------------------------------------------

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
    @DisplayName("AC2: the OWASP step is gated on the preflight and never fails open")
    void owaspStepIsGatedAndEnforced() throws IOException {
        Map<String, Object> securityScan = securityScanJob();
        Map<String, Object> owasp = stepsOf(securityScan).stream()
                .filter(step -> "OWASP Dependency-Check".equals(step.get("name")))
                .filter(step -> step.containsKey("run"))
                .findFirst()
                .orElse(null);
        assertNotNull(owasp, "security-scan must declare the OWASP step");

        // The step must only run when the preflight resolved a key. This is
        // what keeps develop merges unblocked when the key is absent, while
        // still running the scan (and enforcing it) on main.
        String condition = String.valueOf(owasp.get("if"));
        assertTrue(condition.contains("key_present"),
                "the OWASP step must be gated on the preflight's key_present output");

        // No failure-tolerance attributes anywhere on the step.
        assertFalse(owasp.containsKey("continue-on-error"),
                "the OWASP step must never be allowed to fail open");
        String run = String.valueOf(owasp.get("run"));
        assertTrue(run.contains("-DfailBuildOnCVSS=7"),
                "the OWASP step must fail on CVSS >= 7");
        assertFalse(run.contains("-Ddependency-check.skip"),
                "the OWASP step must not skip itself");
    }

    // ------------------------------------------------------------------
    // AC2 - secret scanning cannot be silenced
    // ------------------------------------------------------------------

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
    @DisplayName("GHCR actor username is allowed but plaintext usernames remain violations")
    void githubActorUsernameIsNotTreatedAsASecret() {
        String workflow = "with:\n"
                + "  username: ${{ github.actor }}\n"
                + "  password: ${{ secrets.GITHUB_TOKEN }}\n";

        assertTrue(WorkflowSecretScanner.findCredentialViolations(workflow).isEmpty(),
                "the public GitHub actor is a valid GHCR username, not a secret");

        List<String> plaintextUsernameViolations = WorkflowSecretScanner.findCredentialViolations(
                "with:\n  username: deploy-user\n");
        assertTrue(plaintextUsernameViolations.stream().anyMatch(v -> v.contains("username")),
                "a literal username must still be reported");
    }

    @Test
    @DisplayName("step output status keys are not treated as credentials")
    void stepOutputStatusKey_isNotReportedAsCredential() {
        String workflow = "jobs:\n"
                + "  security-scan:\n"
                + "    outputs:\n"
                + "      nvd-key-present: ${{ steps.preflight.outputs.key_present }}\n";

        assertTrue(WorkflowSecretScanner.findCredentialViolations(workflow).isEmpty(),
                "a boolean key-presence output is not a credential value");
    }

    @Test
    @DisplayName("AC2: secret scanning cannot be silenced or bypassed in the security-scan stage")
    void secretScanningCannotBeSilenced() throws IOException {
        Map<String, Object> securityScan = securityScanJob();
        Map<String, Object> gitleaks = stepNamed(securityScan, "Secret scanning");
        assertNotNull(gitleaks, "the security scan must run gitleaks");

        // gitleaks must not be gated on the NVD key: it is cheap and must
        // still protect develop merges when the key is absent.
        String condition = String.valueOf(gitleaks.get("if"));
        assertFalse(condition.contains("key_present"),
                "gitleaks must not be gated on the NVD key");

        String run = String.valueOf(gitleaks.get("run"));
        assertTrue(run.contains("gitleaks"),
                "the secret scanning step must invoke gitleaks");
        assertFalse(run.contains("--exit-code 0"),
                "gitleaks must keep its non-zero exit code when secrets are found");
        assertFalse(securityScan.containsKey("continue-on-error"),
                "the security scan job must never fail open");
        assertFalse(gitleaks.containsKey("continue-on-error"),
                "the gitleaks step must never fail open");

        String workflow = Files.readString(WORKFLOW);
        Map<String, Object> parsedWorkflow = new Yaml().load(workflow);
        @SuppressWarnings("unchecked")
        Map<String, Object> jobs = (Map<String, Object>) parsedWorkflow.get("jobs");
        assertNotNull(jobs, "the workflow must define jobs");
        for (Map.Entry<String, Object> entry : jobs.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> job = (Map<String, Object>) entry.getValue();
            assertFalse(isEnabled(job.get("continue-on-error")),
                    entry.getKey() + " job must not fail open");
            for (Map<String, Object> step : stepsOf(job)) {
                assertFalse(isEnabled(step.get("continue-on-error")),
                        entry.getKey() + " step '" + step.get("name") + "' must not fail open");
            }
        }
        assertTrue(workflow.contains("${{ secrets.NVD_API_KEY }}"),
                "the optional NVD API key must come from GitHub Secrets (AC3)");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> stepsOf(Map<String, Object> job) {
        List<Map<String, Object>> steps = (List<Map<String, Object>>) job.get("steps");
        assertNotNull(steps, "each job must declare steps");
        return steps;
    }

    private static boolean isEnabled(Object value) {
        return value != null && !Boolean.FALSE.equals(value)
                && !"false".equalsIgnoreCase(String.valueOf(value).trim());
    }

    // ------------------------------------------------------------------
    // AC2b - branch-aware NVD key policy
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC2: the NVD key policy is branch-aware (main strict, develop lenient)")
    void nvdKeyPolicyIsBranchAware() throws IOException {
        String workflow = Files.readString(WORKFLOW);

        // The preflight must exist and resolve both the key and the target
        // branch. `base_ref` is what a pull_request uses for its target.
        assertTrue(workflow.contains("Preflight"),
                "security-scan must declare a preflight step");
        assertTrue(workflow.contains("NVD_API_KEY"),
                "the preflight must inspect NVD_API_KEY");
        assertTrue(workflow.contains("base_ref") || workflow.contains("TARGET_BRANCH"),
                "the preflight must resolve the target branch");

        // main is strict: a missing key must fail the job (and therefore
        // block the merge via ci-gate).
        assertTrue(workflow.contains("required for 'main'")
                        || workflow.contains("required for main"),
                "main must treat a missing NVD key as a hard failure");
        assertTrue(workflow.contains("exit 1"),
                "the preflight must exit non-zero for main when the key is missing");

        // develop is lenient: a missing key must be a notice, not a failure.
        assertTrue(workflow.contains("skipped for 'develop'")
                        || workflow.contains("skipped for develop")
                        || workflow.contains("NOT blocked"),
                "develop must treat a missing NVD key as informational only");

        // The preflight must publish the key_present output consumed by the
        // OWASP step's `if:` guard.
        assertTrue(workflow.contains("key_present="),
                "the preflight must export key_present for the OWASP step");
    }

    // ------------------------------------------------------------------
    // AC3 - gitleaks allowlist cannot neutralise workflow scanning
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // AC1 - dependency review wiring and report publication
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC1: dependency review runs on pull requests and the OWASP report is published")
    void securityScanStageWiring() throws IOException {
        Map<String, Object> securityScan = securityScanJob();

        Map<String, Object> depReview = stepNamed(securityScan, "Dependency review");
        assertNotNull(depReview, "dependency review must run on PRs");
        String uses = String.valueOf(depReview.get("uses"));
        assertTrue(uses.contains("actions/dependency-review-action"),
                "dependency review (GitHub Advisory DB) must run on PRs");
        assertTrue(String.valueOf(depReview.get("if")).contains("pull_request"),
                "dependency review must stay PR-scoped");
        @SuppressWarnings("unchecked")
        Map<String, Object> with = (Map<String, Object>) depReview.get("with");
        assertNotNull(with, "dependency review must declare its inputs");
        assertEquals("high", String.valueOf(with.get("fail-on-severity")),
                "dependency review must fail on high severity");

        // The OWASP report must be uploaded (when the scan runs) so findings
        // are inspectable.
        Map<String, Object> report = stepsOf(securityScan).stream()
                .filter(step -> "Upload dependency-check report".equals(step.get("name")))
                .filter(step -> "actions/upload-artifact@v4".equals(step.get("uses")))
                .filter(step -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> artifactInputs = (Map<String, Object>) step.get("with");
                    return artifactInputs != null
                            && "dependency-check-report".equals(artifactInputs.get("name"));
                })
                .findFirst()
                .orElse(null);
        assertNotNull(report, "the OWASP report must be uploaded");
        String reportCondition = String.valueOf(report.get("if"));
        assertTrue(reportCondition.contains("always()"),
                "the report upload must run even when the scan fails");
    }
}