package com.onecare.backend.ci;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the CI pipeline definition.
 *
 * <p>
 * The workflow file itself is the test data: every acceptance criterion that
 * can be violated by editing {@code .github/workflows/ci.yml} is asserted here,
 * so a PR that weakens the pipeline is caught by the very pipeline it weakens.
 * </p>
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
                        "AKIA[0-9A-Z]{16}|" // AWS access key id
                                        + "ghp_[A-Za-z0-9]{36}|" // GitHub personal token
                                        + "github_pat_[A-Za-z0-9_]{22,}|" // GitHub fine-grained token
                                        + "glpat-[A-Za-z0-9_-]{20,}|" // GitLab token
                                        + "xox[baprs]-[A-Za-z0-9-]{10,}|" // Slack token
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

        /**
         * All step `name`s of a job, concatenated - these are the labels shown in the
         * CI log.
         */
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
        private static Map<String, Object> findStepEnvContaining(
                        Map<String, Object> job, String envKey) {
                for (Map<String, Object> step : stepsOf(job)) {
                        Object env = step.get("env");
                        if (env instanceof Map<?, ?> m && m.containsKey(envKey)) {
                                return (Map<String, Object>) m;
                        }
                }
                throw new AssertionError(
                                "the test command must define an env block containing " + envKey);
        }

        /** The step whose `name` matches the given substring, or null. */
        private static Map<String, Object> stepNamed(Map<String, Object> job, String nameSubstring) {
                for (Map<String, Object> step : stepsOf(job)) {
                        Object name = step.get("name");
                        if (name != null && String.valueOf(name).contains(nameSubstring)) {
                                return step;
                        }
                }
                return null;
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
        @DisplayName("lint, static analysis, security scan, build, test, coverage gate and Docker build are separate jobs (separate checks)")
        void ac1_everyStageIsItsOwnJobSoEachReportsAsItsOwnCheck() {
                Map<String, Object> jobs = jobs();
                for (String stage : STAGES) {
                        Map<String, Object> job = job(stage);
                        assertTrue(jobs.containsKey(stage), "stage missing: " + stage);
                        assertNotNull(job.get("runs-on"), stage + " must run on a runner");
                        assertFalse(stepsOf(job).isEmpty(), stage + " must execute at least one step");
                }

                // Only stages that *consume an artifact* may declare `needs`. Every
                // other stage must run independently so it reports as its own check.
                // coverage-gate <- test (consumes coverage-data)
                // docker-build <- build (consumes backend-jar)
                Map<String, List<String>> expectedNeeds = Map.of(
                                "coverage-gate", List.of("test"),
                                "docker-build", List.of("build"));
                for (String stage : STAGES) {
                        List<String> actual = needsOf(job(stage));
                        if (expectedNeeds.containsKey(stage)) {
                                assertEquals(expectedNeeds.get(stage), actual,
                                                stage + " must consume exactly the artifact(s) it declares");
                        } else {
                                assertTrue(actual.isEmpty(),
                                                stage + " must run independently so it reports on its own");
                        }
                }
        }

        @Test
        @DisplayName("each stage runs its documented tool")
        void ac1_stagesRunTheExpectedTooling() {
                assertTrue(runScriptOf(job("lint")).contains("checkstyle:check"),
                                "lint stage must run Checkstyle (backend lint)");
                assertTrue(runScriptOf(job("static-analysis")).contains("spotbugs:check"),
                                "static-analysis stage must run SpotBugs");
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
               assertTrue(
                stepsOf(job("docker-build")).stream()
                        .map(step -> String.valueOf(step.get("uses")))
                        .anyMatch(uses -> uses.contains("docker/build-push-action")),
                "docker-build stage must build the container image");
        }

        @Test
        @DisplayName("the aggregate ci-gate job depends on every stage")
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
        @DisplayName("no stage may continue on error or swallow failures")
        void ac2_noStageMayFailOpen() {
                for (String stage : STAGES) {
                        Map<String, Object> stageJob = job(stage);
                        assertFalse(isEnabled(stageJob.get("continue-on-error")),
                                        stage + " job must not continue on error");
                        for (Map<String, Object> step : stepsOf(stageJob)) {
                                assertFalse(isEnabled(step.get("continue-on-error")),
                                                stage + " step '" + step.get("name")
                                                                + "' must not continue on error");
                        }

                        String script = runScriptOf(stageJob);
                        for (String flag : FAILURE_TOLERANCE_FLAGS) {
                                assertFalse(script.contains(flag),
                                                stage + " must not use " + flag + " (it would swallow failures)");
                        }
                        assertFalse(script.contains("|| true"),
                                        stage + " must not mask failures with `|| true`");
                }
        }

        private static boolean isEnabled(Object value) {
                return value != null && !Boolean.FALSE.equals(value)
                                && !"false".equalsIgnoreCase(String.valueOf(value).trim());
        }

        @Test
        @DisplayName("the failing stage is named by the gate that blocks the merge")
        void ac2_failingStageIsNamedByTheBlockingGate() {
                String script = runScriptOf(job("ci-gate"));
                assertTrue(script.contains("CI stage"),
                                "the gate must report the failing stage name");
                assertTrue(script.contains("pull request is blocked"),
                                "the gate must state that the PR is blocked");
                for (String stage : STAGES) {
                        assertTrue(job(stage).containsKey("runs-on"), stage + " must be a real check");
                }
        }

        // ------------------------------------------------------------------
        // AC2b - branch-aware NVD key policy (main strict, develop lenient)
        // ------------------------------------------------------------------

        @Test
        @DisplayName("security-scan encodes a branch-aware NVD key policy")
        void ac2_securityScanIsBranchAwareForTheNvdKey() {
                Map<String, Object> securityScan = job("security-scan");
                String script = runScriptOf(securityScan);
                String names = stepNamesOf(securityScan);

                // A preflight step must exist and decide the policy *before* the
                // expensive scan runs.
                assertNotNull(stepNamed(securityScan, "Preflight"),
                                "security-scan must declare a preflight step that resolves the NVD key policy");
                assertTrue(script.contains("NVD_API_KEY"),
                                "the preflight must inspect NVD_API_KEY");
                assertTrue(script.contains("TARGET_BRANCH") || script.contains("base_ref"),
                                "the preflight must resolve the target branch (base_ref for pull_request)");

                // main must be strict: a missing key fails the preflight.
                assertTrue(script.contains("required for 'main'")
                                || script.contains("required for main"),
                                "main must treat a missing NVD key as a hard failure");
                assertTrue(script.contains("exit 1"),
                                "the preflight must exit non-zero when the policy requires the key");

                // develop must be lenient: a missing key must NOT fail the preflight.
                assertTrue(script.contains("skipped for 'develop'")
                                || script.contains("skipped for develop")
                                || script.contains("NOT blocked"),
                                "develop must treat a missing NVD key as informational only");

                // The scan itself stays enforced when it runs.
                assertFalse(script.contains("continue-on-error"),
                                "the OWASP scan must never be allowed to fail open");
                Map<String, Object> owasp = stepsOf(securityScan).stream()
                                .filter(step -> "OWASP Dependency-Check".equals(step.get("name")))
                                .filter(step -> step.containsKey("run"))
                                .findFirst()
                                .orElse(null);
                assertNotNull(owasp, "security-scan must declare the OWASP step");
                assertTrue(String.valueOf(owasp.get("if")).contains("key_present"),
                                "the OWASP step must be gated on the preflight's key_present output");
                String owaspScript = String.valueOf(owasp.get("run"));
                assertTrue(owaspScript.contains("failBuildOnCVSS=7"),
                                "the OWASP step must fail on CVSS >= 7");
                assertFalse(owaspScript.contains("continue-on-error"),
                                "the OWASP step must never be allowed to fail open");

                // gitleaks and dependency-review must remain unconditional: they are
                // cheap, do not need the NVD key, and still protect develop merges.
                assertNotNull(stepNamed(securityScan, "Secret scanning"),
                                "gitleaks must remain part of security-scan");
                Map<String, Object> gitleaks = stepNamed(securityScan, "Secret scanning");
                assertFalse(String.valueOf(gitleaks.get("if")).contains("key_present"),
                                "gitleaks must not be gated on the NVD key");
                Map<String, Object> depReview = stepNamed(securityScan, "Dependency review");
                assertNotNull(depReview, "dependency-review must remain part of security-scan");
                assertTrue(String.valueOf(depReview.get("if")).contains("pull_request"),
                                "dependency-review must stay PR-scoped");
        }

        // ------------------------------------------------------------------
        // AC3 - no plaintext secrets in the workflow files
        // ------------------------------------------------------------------

        @Test
        @DisplayName("workflow credentials come from GitHub Secrets apart from disposable test values")
        void ac3_allCredentialsComeFromGitHubSecrets() throws IOException {
                assertTestDatabaseCredentialsAreScopedToTestJob();
                Set<String> allowedTestDatabaseCredentials = Set.of(
                                "credential key 'MYSQL_USER' is not sourced from GitHub Secrets (value: test)",
                                "credential key 'MYSQL_PASSWORD' is not sourced from GitHub Secrets (value: test)",
                                "credential key 'MYSQL_ROOT_PASSWORD' is not sourced from GitHub Secrets (value: root)",
                                "credential key 'SPRING_DATASOURCE_USERNAME' is not sourced from GitHub Secrets (value: test)",
                                "credential key 'SPRING_DATASOURCE_PASSWORD' is not sourced from GitHub Secrets (value: test)");
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
                        Set<String> unexpectedViolations = violations.stream()
                                        .map(violation -> violation.substring(violation.indexOf(": ") + 2))
                                        .filter(violation -> !workflowFile.equals(CI_WORKFLOW)
                                                        || !allowedTestDatabaseCredentials.contains(violation))
                                        .collect(java.util.stream.Collectors.toSet());
                        assertTrue(unexpectedViolations.isEmpty(),
                                        () -> workflowFile + " contains unexpected plaintext credentials:\n"
                                                        + String.join("\n", unexpectedViolations));
                }
        }

        @SuppressWarnings("unchecked")
        private static void assertTestDatabaseCredentialsAreScopedToTestJob() {
                Map<String, Object> testJob = job("test");
                Map<String, Object> services = (Map<String, Object>) testJob.get("services");
                assertNotNull(services, "the test job must define its MySQL service");
                Map<String, Object> mysql = (Map<String, Object>) services.get("mysql");
                assertNotNull(mysql, "the test job must define its MySQL service");
                Map<String, Object> mysqlEnv = (Map<String, Object>) mysql.get("env");
                assertNotNull(mysqlEnv, "the MySQL service must define its environment");
                assertCredentialValue(mysqlEnv.get("MYSQL_USER"), "test", "CI_MYSQL_USER");
                assertCredentialValue(mysqlEnv.get("MYSQL_PASSWORD"), "test", "CI_MYSQL_PASSWORD");
                assertCredentialValue(mysqlEnv.get("MYSQL_ROOT_PASSWORD"), "root", "CI_MYSQL_ROOT_PASSWORD");

                Map<String, Object> testStepEnv = stepsOf(testJob).stream()
                                .map(step -> (Map<String, Object>) step.get("env"))
                                .filter(env -> env != null && env.containsKey("SPRING_DATASOURCE_USERNAME"))
                                .findFirst()
                                .orElseThrow(() -> new AssertionError(
                                                "the test command must define its datasource credentials"));
                assertCredentialValue(testStepEnv.get("SPRING_DATASOURCE_USERNAME"),
                                "test", "CI_MYSQL_USER");
                assertCredentialValue(testStepEnv.get("SPRING_DATASOURCE_PASSWORD"),
                                "test", "CI_MYSQL_PASSWORD");
        }

        private static void assertCredentialValue(Object value, String testDefault, String secretName) {
                String actual = String.valueOf(value);
                assertTrue(actual.equals(testDefault)
                                || actual.equals("${{ secrets." + secretName + " || '" + testDefault + "' }}")
                                || actual.equals("${{ secrets." + secretName + " }}"),
                                () -> secretName + " must use its disposable test default or GitHub Secret, got: "
                                                + actual);
        }

        @Test
        @DisplayName("JWT secret comes from GitHub Secrets in CI and uses a test-only local fallback")
        void ac3_forkPrFallbacksAreTestOnlyPlaceholders() {
                Map<String, Object> testJob = job("test");
                Map<String, Object> testStepEnv = findStepEnvContaining(testJob, "JWT_SECRET");

                assertEquals("${{ secrets.JWT_SECRET }}", testStepEnv.get("JWT_SECRET"),
                                "CI must pass the JWT key from GitHub Secrets without an inline fallback");

                Properties testProperties = new Properties();
                try (var input = Files.newInputStream(Path.of("src", "test", "resources", "application.properties"))) {
                        testProperties.load(input);
                } catch (IOException e) {
                        throw new AssertionError("cannot read test application properties", e);
                }
                String jwtProperty = testProperties.getProperty("jwt.secret");
                assertNotNull(jwtProperty, "test properties must define a JWT secret");
                assertTrue(jwtProperty.startsWith("${JWT_SECRET:") && jwtProperty.endsWith("}"),
                                "the fallback must be scoped to the test-only JWT property");
                String fallback = jwtProperty.substring("${JWT_SECRET:".length(), jwtProperty.length() - 1);
                String decodedFallback = new String(Base64.getDecoder().decode(fallback),
                                java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(decodedFallback.startsWith("local-test-jwt-signing-key-for-unit-tests"),
                                "the test-only JWT fallback must be an unmistakable local test placeholder");
                assertFalse(WELL_KNOWN_SECRET_FORMATS.matcher(fallback).find(),
                                "the test JWT fallback must not match any well-known secret format");
        }

        @Test
        @DisplayName("no well-known secret formats appear anywhere in the workflows")
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
        @DisplayName("the workflow token is least-privilege and secret references are used")
        void ac3_leastPrivilegeToken() {
                assertTrue(
                                String.valueOf(((Map<?, ?>) workflow.get("concurrency")).get("group"))
                                                .endsWith("${{ github.ref }}"),
                                "concurrency group must be ref-scoped to cancel superseded runs");
                Object permissions = workflow.get("permissions");
                assertNotNull(permissions, "workflow must declare explicit permissions");
                assertEquals("read", ((Map<?, ?>) permissions).get("contents"),
                                "workflow must be read-only at the top level");
                assertTrue(workflowText.contains("${{ secrets."),
                                "credentials must be read from the secrets context");
        }

        @Test
        @DisplayName("security-scan requests only the extra scopes dependency-review needs")
        @SuppressWarnings("unchecked")
        void ac3_securityScanJobPermissionsAreScopedToDependencyReview() {
                Map<String, Object> securityScan = job("security-scan");
                Object permissions = securityScan.get("permissions");
                assertNotNull(permissions,
                                "security-scan must declare job-level permissions");
                Map<String, Object> perms = (Map<String, Object>) permissions;
                assertEquals("read", perms.get("contents"),
                                "security-scan must keep contents read-only");
                assertEquals("read", perms.get("pull-requests"),
                                "security-scan needs pull-requests: read for dependency-review");
                // No write scopes anywhere.
                perms.forEach((key, value) -> assertFalse(String.valueOf(value).equals("write"),
                                "security-scan must not request write permission for " + key));
        }

        // ------------------------------------------------------------------
        // AC4 - coverage gate is its own step/job, reported on its own
        // ------------------------------------------------------------------

        @Test
        @DisplayName("coverage-gate is a separate job after test; test never runs the gate")
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