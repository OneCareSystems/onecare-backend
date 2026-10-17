package com.onecare.backend.ci;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the CI pipeline definition.
 *
 * <p>The workflow file itself is the test data: every acceptance criterion that
 * can be violated by editing {@code .github/workflows/ci.yml} is asserted here,
 * so a PR that weakens the pipeline is caught by the very pipeline it weakens.</p>
 */
class CiWorkflowConfigurationTest {

    private static final Path WORKFLOW_DIR = Path.of(".github", "workflows");
    private static final Path CI_WORKFLOW = WORKFLOW_DIR.resolve("ci.yml");

    /** SDS 2.6.5 stages 1-4 as implemented - each one is its own job/check. */
    private static final List<String> STAGES = List.of(
            "lint", "static-analysis", "security-scan", "build",
            "test", "coverage-gate", "docker-build");

    /** Flags that would let a failing stage pass silently (AC2). */
    private static final List<String> FAILURE_TOLERANCE_FLAGS = List.of(
            "--fail-never", "-fae", "--fail-at-end",
            "-Dmaven.test.failure.ignore", "-Dcheckstyle.skip",
            "-Dspotbugs.skip", "-Djacoco.skip", "-Ddependency-check.skip");

    private static final Pattern WELL_KNOWN_SECRET_FORMATS = Pattern.compile(
            "AKIA[0-9A-Z]{16}|"                 // AWS access key id
            + "ghp_[A-Za-z0-9]{36}|"            // GitHub personal token
            + "github_pat_[A-Za-z0-9_]{22,}|"   // GitHub fine-grained token
            + "glpat-[A-Za-z0-9_-]{20,}|"       // GitLab token
            + "xox[baprs]-[A-Za-z0-9-]{10,}|"   // Slack token
            + "-----BEGIN [A-Z ]*PRIVATE KEY");

    private static String workflowText;
    private static Map<String, Object> workflow;

    @BeforeAll
    static void loadWorkflow() throws IOException {
        workflowText = Files.readString(CI_WORKFLOW);
        workflow = new Yaml().load(workflowText);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jobs() {
        Map<String, Object> jobs = (Map<String, Object>) workflow.get("jobs");
        assertNotNull(jobs, "the workflow must define jobs");
        return jobs;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> job(String name) {
        Map<String, Object> job = (Map<String, Object>) jobs().get(name);
        assertNotNull(job, () -> "missing CI stage job: " + name);
        return job;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> stepsOf(Map<String, Object> job) {
        List<Map<String, Object>> steps = (List<Map<String, Object>>) job.get("steps");
        assertNotNull(steps, "every stage must declare steps");
        return steps;
    }

    /** All `run` commands of a job, concatenated. */
    private static String runScriptOf(Map<String, Object> job) {
        StringBuilder script = new StringBuilder();
        for (Map<String, Object> step : stepsOf(job)) {
            Object run = step.get("run");
            if (run != null) {
                script.append(run).append('\n');
            }
        }
        return script.toString();
    }

    /** All step `name`s of a job, concatenated - these are the labels shown in the CI log. */
    private static String stepNamesOf(Map<String, Object> job) {
        StringBuilder names = new StringBuilder();
        for (Map<String, Object> step : stepsOf(job)) {
            Object name = step.get("name");
            if (name != null) {
                names.append(name).append('\n');
            }
        }
        return names.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> needsOf(Map<String, Object> job) {
        Object needs = job.get("needs");
        if (needs == null) {
            return List.of();
        }
        if (needs instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            list.forEach(item -> result.add(String.valueOf(item)));
            return result;
        }
        return List.of(String.valueOf(needs));
    }

    // ------------------------------------------------------------------
    // AC1 - happy path: every stage executes and reports independently
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC1: lint, static analysis, security scan, build, test, coverage gate and Docker build are separate jobs (separate checks)")
    void ac1_everyStageIsItsOwnJobSoEachReportsAsItsOwnCheck() {
        Map<String, Object> jobs = jobs();
        for (String stage : STAGES) {
            Map<String, Object> job = job(stage);
            assertTrue(jobs.containsKey(stage), "stage missing: " + stage);
            assertNotNull(job.get("runs-on"), stage + " must run on a runner");
            assertFalse(stepsOf(job).isEmpty(), stage + " must execute at least one step");
        }
        // independent: only coverage-gate waits for test (it consumes its artifact)
        for (String stage : STAGES) {
            if (stage.equals("coverage-gate")) {
                assertEquals(List.of("test"), needsOf(job(stage)),
                        "coverage-gate must consume the test stage's coverage data");
            } else if (!stage.equals("ci-gate")) {
                assertTrue(needsOf(job(stage)).isEmpty(),
                        stage + " must run independently so it reports on its own");
            }
        }
    }

    @Test
    @DisplayName("AC1: each stage runs its documented tool")
    void ac1_stagesRunTheExpectedTooling() {
        assertTrue(runScriptOf(job("lint")).contains("checkstyle:check"),
                "lint stage must run Checkstyle (backend lint)");
        assertTrue(runScriptOf(job("static-analysis")).contains("spotbugs:check"),
                "static-analysis stage must run SpotBugs (SDS 2.1.6 / 2.5.5)");
        String securityScan = runScriptOf(job("security-scan"));
        assertTrue(securityScan.contains("dependency-check-maven"),
                "security-scan must run OWASP Dependency-Check");
        assertTrue(securityScan.contains("gitleaks"),
                "security-scan must run secret scanning");
        assertTrue(runScriptOf(job("build")).contains("package"),
                "build stage must package the application");
        assertTrue(runScriptOf(job("test")).contains("clean test"),
                "test stage must execute the test suite");
        assertTrue(runScriptOf(job("coverage-gate")).contains("jacoco:check"),
                "coverage-gate stage must enforce the JaCoCo gate");
        assertTrue(runScriptOf(job("docker-build")).contains("docker build"),
                "docker-build stage must build the container image (SDS 2.6.5 stage 4)");
    }

    @Test
    @DisplayName("AC1: the aggregate ci-gate job depends on every stage")
    void ac1_aggregateGateDependsOnEveryStage() {
        Map<String, Object> ciGate = job("ci-gate");
        assertEquals(new LinkedHashSet<>(STAGES), new LinkedHashSet<>(needsOf(ciGate)),
                "ci-gate must aggregate every stage");
        assertTrue(String.valueOf(ciGate.get("if")).contains("always()"),
                "ci-gate must evaluate even when a stage failed");
        String script = runScriptOf(ciGate);
        for (String stage : STAGES) {
            assertTrue(script.contains("report " + stage + " "),
                    "ci-gate must name stage '" + stage + "' in its report");
        }
        assertTrue(script.contains("exit $fail"),
                "ci-gate must exit non-zero when a stage did not succeed");
    }

    // ------------------------------------------------------------------
    // AC2 - a failing lint/security-scan check blocks the merge
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC2: no stage may continue on error or swallow failures")
    void ac2_noStageMayFailOpen() {
        assertFalse(workflowText.contains("continue-on-error"),
                "continue-on-error would let a failing check pass and must not be used");
        for (String stage : STAGES) {
            String script = runScriptOf(job(stage));
            for (String flag : FAILURE_TOLERANCE_FLAGS) {
                assertFalse(script.contains(flag),
                        stage + " must not use " + flag + " (it would swallow failures)");
            }
            assertFalse(script.contains("|| true"),
                    stage + " must not mask failures with `|| true`");
        }
    }

    @Test
    @DisplayName("AC2: the failing stage is named by the gate that blocks the merge")
    void ac2_failingStageIsNamedByTheBlockingGate() {
        String script = runScriptOf(job("ci-gate"));
        assertTrue(script.contains("CI stage"),
                "the gate must report the failing stage name");
        assertTrue(script.contains("pull request is blocked"),
                "the gate must state that the PR is blocked");
        // the stage checks themselves are the required checks: each job has an
        // explicit, stage-named identity
        for (String stage : STAGES) {
            assertTrue(job(stage).containsKey("runs-on"), stage + " must be a real check");
        }
    }

    // ------------------------------------------------------------------
    // AC3 - no plaintext secrets in the workflow files
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC3: every credential in every workflow file comes from GitHub Secrets")
    void ac3_allCredentialsComeFromGitHubSecrets() throws IOException {
        List<Path> workflows;
        try (var stream = Files.list(WORKFLOW_DIR)) {
            workflows = stream
                    .filter(path -> path.getFileName().toString().endsWith(".yml")
                            || path.getFileName().toString().endsWith(".yaml"))
                    .sorted()
                    .toList();
        }
        assertFalse(workflows.isEmpty(), "no workflow files found");
        for (Path workflowFile : workflows) {
            List<String> violations = WorkflowSecretScanner.findCredentialViolations(
                    Files.readString(workflowFile));
            assertTrue(violations.isEmpty(),
                    () -> workflowFile + " contains plaintext credentials:\n"
                            + String.join("\n", violations));
        }
    }

    @Test
    @DisplayName("AC3: no well-known secret formats appear anywhere in the workflows")
    void ac3_noWellKnownSecretFormats() throws IOException {
        List<Path> workflows;
        try (var stream = Files.list(WORKFLOW_DIR)) {
            workflows = stream.toList();
        }
        for (Path workflowFile : workflows) {
            String name = workflowFile.getFileName().toString();
            if (!name.endsWith(".yml") && !name.endsWith(".yaml")) {
                continue;
            }
            String text = Files.readString(workflowFile);
            var matcher = WELL_KNOWN_SECRET_FORMATS.matcher(text);
            boolean found = matcher.find();
            String offending = found ? matcher.group() : "";
            assertFalse(found,
                    () -> workflowFile + " contains a well-known secret format: " + offending);
        }
    }

    @Test
    @DisplayName("AC3: the workflow token is least-privilege and secret references are used")
    void ac3_leastPrivilegeToken() {
        assertTrue(
                String.valueOf(((Map<?, ?>) workflow.get("concurrency")).get("group"))
                        .endsWith("${{ github.ref }}"),
                "concurrency group must be ref-scoped to cancel superseded runs");
        Object permissions = workflow.get("permissions");
        assertNotNull(permissions, "workflow must declare explicit permissions");
        assertEquals("read", ((Map<?, ?>) permissions).get("contents"),
                "workflow must be read-only apart from the dependency-review PR comment");
        assertTrue(workflowText.contains("${{ secrets."),
                "credentials must be read from the secrets context");
    }

    // ------------------------------------------------------------------
    // AC4 - coverage gate is its own step/job, reported on its own
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC4: coverage-gate is a separate job after test; test never runs the gate")
    void ac4_coverageGateIsReportedOnItsOwn() {
        Map<String, Object> coverageGate = job("coverage-gate");
        assertEquals(List.of("test"), needsOf(coverageGate));
        String gateScript = runScriptOf(coverageGate);
        assertTrue(gateScript.contains("jacoco:check"));
        assertTrue(stepNamesOf(coverageGate).contains("coverage >= 70%"),
                "the failing step must be named after the gate it enforces");
        assertFalse(runScriptOf(job("test")).contains("jacoco:check"),
                "the test stage must never run the coverage gate (AC4)");
        assertFalse(needsOf(job("test")).contains("coverage-gate"),
                "the test stage must not depend on the coverage gate (AC4)");
    }

    @Test
    @DisplayName("known deviation: staging deploy and monitoring stages stay deferred (SDS 2.6.5 stages 5-6)")
    void knownDeviation_stagesFiveAndSixAreDeferred() {
        assertFalse(jobs().containsKey("staging-deploy"),
                "staging deploy is a documented deviation and must stay out of this workflow");
        assertFalse(jobs().containsKey("monitoring"),
                "monitoring is a documented deviation and must stay out of this workflow");
        // the deviation must stay documented
        Path doc = Path.of("docs", "CI_CD_PIPELINE.md");
        assertTrue(Files.exists(doc), "docs/CI_CD_PIPELINE.md must document the deviation");
        try {
            String text = Files.readString(doc);
            assertTrue(text.contains("deferred"), "deviation must be recorded as deferred");
            assertTrue(text.contains("SonarQube"), "SonarQube substitution must be documented");
        } catch (IOException e) {
            throw new AssertionError("cannot read " + doc, e);
        }
    }
}
