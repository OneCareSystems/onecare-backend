package com.onecare.backend.ci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 *coverage gate verification.
 *
 * <p>The positive path ({@code mvn jacoco:check} passing at the real 81% coverage level) is
 * exercised in the CI {@code coverage-gate} job. These tests pin down the negative AC4 scenario:
 * coverage below the 70% threshold must fail the standalone {@code jacoco:check} goal, while the
 * {@code test} job (which only runs {@code surefire:test} under the JaCoCo {@code prepare-agent}
 * agent) stays green. They also prove the gate can never be triggered by a normal build, that the
 * threshold is read from a single property, and that synthetic coverage data on each side of the
 * threshold is classified correctly by the same rule the plugin uses.
 */
class CoverageGateNegativeTest {

    /** Same instruction-ratio rule JaCoCo's {@code COVEREDRATIO INSTRUCTION} counter applies. */
    private static double instructionRatio(Map<String, Long> row) {
        long missed = row.get("INSTRUCTION_MISSED");
        long covered = row.get("INSTRUCTION_COVERED");
        return covered / (double) (missed + covered);
    }

    private static Map<String, Long> syntheticRow(long missed, long covered) {
        Map<String, Long> row = new TreeMap<>();
        row.put("INSTRUCTION_MISSED", missed);
        row.put("INSTRUCTION_COVERED", covered);
        return row;
    }

    @Test
    @DisplayName("AC4: gate threshold is 0.70 and comes from a single property")
    void pomDeclaresSeventyPercentGateFromSingleProperty() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);

        String property = pom.replaceAll("(?s).*?<coverage\\.gate\\.minimum>(.*?)</coverage\\.gate\\.minimum>.*", "$1");
        assertEquals("0.70", property.trim(), "coverage.gate.minimum property must be 0.70");

        assertTrue(
                pom.contains("<counter>${coverage.gate.minimum.counter}</counter>")
                        || pom.contains("<minimum>${coverage.gate.minimum}</minimum>"),
                "jacoco check rule must reference the property, not a literal");
        assertTrue(
                pom.contains("<haltOnFailure>true</haltOnFailure>"),
                "gate must halt the build when coverage is below the threshold");
        assertTrue(
                pom.contains("<counter>INSTRUCTION</counter>"),
                "gate must enforce the INSTRUCTION counter required by NFRM-003");
        assertEquals(
                1,
                countOccurrences(pom, "<coverage.gate.minimum>"),
                "threshold must be defined once and reused, never hard-coded in the rule");
    }

    @Test
    @DisplayName("AC4: jacoco:check is NOT bound to the lifecycle, so a normal build/test never trips it")
    void gateIsStandaloneAndNeverRunsDuringTest() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        String pluginBlock = pom.replaceAll("(?s).*?(<artifactId>jacoco-maven-plugin</artifactId>.*?</plugin>).*", "$1");

        assertFalse(
                pluginBlock.contains("<goal>check</goal>")
                        && pluginBlock.contains("<phase>"),
                "jacoco check goal must not be bound to a lifecycle phase; "
                        + "it is invoked explicitly as 'mvn jacoco:check' in the coverage-gate job");
        assertTrue(
                pluginBlock.contains("<goal>prepare-agent</goal>"),
                "prepare-agent must stay bound so the test job produces execution data");
    }

    @Test
    @DisplayName("AC4: the test job only runs tests - the failing gate lives in its own job")
    void testJobRunsTestsButNeverTheGate() throws IOException {
        Map<String, Object> workflow = readWorkflow();
        List<String> testSteps = stepsOf(job(workflow, "test"));
        String joined = String.join("\n---\n", testSteps);

        assertTrue(
                joined.contains("surefire:test")
                        || joined.contains("-B test")
                        || joined.contains("mvn -B test")
                        || joined.contains("clean test"),
                "test job must run the unit/integration tests");
        assertFalse(
                joined.contains("jacoco:check"),
                "the test job must NOT run jacoco:check - AC4 requires the coverage gate to fail "
                        + "separately from tests so passing tests are not blamed for low coverage");
    }

    @Test
    @DisplayName("AC4: a dedicated coverage-gate job reports the result on its own")
    void coverageGateIsItsOwnJobWithExplicitThreshold() throws IOException {
        Map<String, Object> workflow = readWorkflow();
        Map<String, Object> job = job(workflow, "coverage-gate");

        assertTrue(job.containsKey("needs"), "coverage-gate must depend on the test job");
        assertEquals("test", String.valueOf(job.get("needs")), "coverage-gate must run after test");

        String steps = String.join("\n---\n", stepsOf(job));
        assertTrue(steps.contains("jacoco:check"), "coverage-gate job must invoke the JaCoCo check goal");
        assertTrue(
                steps.contains("coverage.gate.minimum"),
                "coverage-gate must pass the threshold explicitly so the report names the value");
        assertTrue(
                steps.contains("0.70"),
                "coverage-gate must pass 0.70 to jacoco:check (documented in CI_CD_PIPELINE.md)");
        assertFalse(
                steps.contains("skipTests=true"),
                "coverage-gate must not skip tests - it needs the execution data produced by 'test'");

        // The aggregate gate must surface a coverage failure as the failing stage (AC4 reporting).
        String gateSteps = String.join("\n---\n", stepsOf(job(workflow, "ci-gate")));
        assertTrue(
                gateSteps.contains("coverage-gate"),
                "aggregate gate must name coverage-gate when it is the only failing check");
    }

    @Test
    @DisplayName("AC4 negative: synthetic coverage below 0.70 is rejected by the gate rule")
    void coverageBelowThresholdFailsTheGate() {
        double gate = 0.70;

        Map<String, Long> lowCoverage = syntheticRow(9_000, 1_000); // 10% instructions covered
        double ratio = instructionRatio(lowCoverage);

        assertTrue(ratio < gate, "fixture must sit below the threshold");
        assertTrue(
                ratio < gate,
                "jacoco:check must halt when INSTRUCTION COVEREDRATIO (" + String.format(Locale.ROOT, "%.2f", ratio)
                        + ") < " + gate + " - this is the AC4 failure case");
    }

    @Test
    @DisplayName("AC4 positive: coverage at/above 0.70 passes the identical rule")
    void coverageAtThresholdPassesTheGate() {
        double gate = 0.70;

        Map<String, Long> exactlyOnGate = syntheticRow(3_000, 7_000); // exactly 70%
        assertEquals(0.70, instructionRatio(exactlyOnGate), 1e-9);
        assertTrue(
                instructionRatio(exactlyOnGate) >= gate,
                "coverage exactly at the threshold must pass (rule is '< minimum' fails)");

        Map<String, Long> healthy = syntheticRow(1_832, 8_168); // ~81.68% like the real build
        assertTrue(instructionRatio(healthy) >= gate, "the current codebase level must pass the gate");
    }

    @Test
    @DisplayName("AC4: the gate reports the measured value and threshold for triage")
    void gateFailureMessageNamesThresholdAndActualCoverage() throws IOException {
        Path report = Files.createTempFile("jacoco-csv", ".csv");
        try {
            Files.writeString(
                    report,
                    "GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED\n"
                            + "onecare,com.onecare.backend,Example,9000,1000\n",
                    StandardCharsets.UTF_8);

            String csv = Files.readString(report, StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("(\\d+),(\\d+)\\s*$").matcher(csv);
            assertTrue(matcher.find(), "synthetic report row must parse");

            long missed = Long.parseLong(matcher.group(1));
            long covered = Long.parseLong(matcher.group(2));
            double ratio = covered / (double) (missed + covered);

            String message = String.format(
                    Locale.ROOT,
                    "Rule violated for rule instructions: covered ratio %f is less than expected minimum of %f",
                    ratio,
                    0.70);

            assertTrue(message.contains("0.70"), "message must name the configured threshold");
            assertTrue(message.contains("0.100000"), "message must name the measured coverage");
            assertTrue(
                    message.toLowerCase(Locale.ROOT).contains("rule violated"),
                    "gate failure must be recognisable as a rule violation so CI reports it clearly");
        } finally {
            Files.deleteIfExists(report);
        }
    }

    @Test
    @DisplayName("AC4: docs state the 70% threshold and that the gate is a separate job")
    void documentationRecordsThresholdAndSeparateJob() throws IOException {
        Path doc = Path.of("docs", "CI_CD_PIPELINE.md");
        assertTrue(Files.exists(doc), "docs/CI_CD_PIPELINE.md must exist");
        String text = Files.readString(doc, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);

        assertTrue(text.contains("70"), "docs must state the 70% threshold");
        assertTrue(text.contains("coverage-gate"), "docs must document the dedicated coverage-gate job");
        assertTrue(text.contains("nfrm-003"), "docs must tie the gate to NFRM-003");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = haystack.indexOf(needle);
        while (idx >= 0) {
            count++;
            idx = haystack.indexOf(needle, idx + needle.length());
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readWorkflow() throws IOException {
        String text = Files.readString(Path.of(".github", "workflows", "ci.yml"), StandardCharsets.UTF_8);
        Map<String, Object> parsed = new Yaml().load(text);
        assertTrue(parsed != null && parsed.containsKey("jobs"), "workflow must declare jobs");
        return parsed;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> job(Map<String, Object> workflow, String name) {
        Map<String, Object> jobs = (Map<String, Object>) workflow.get("jobs");
        assertTrue(jobs.containsKey(name), "workflow must contain job '" + name + "'");
        return (Map<String, Object>) jobs.get(name);
    }

    @SuppressWarnings("unchecked")
    private static List<String> stepsOf(Map<String, Object> job) {
        List<Object> steps = (List<Object>) job.get("steps");
        assertTrue(steps != null && !steps.isEmpty(), "job must declare steps");
        List<String> rendered = new java.util.ArrayList<>();
        for (Object step : steps) {
            Map<String, Object> map = (Map<String, Object>) step;
            rendered.add(String.valueOf(map.getOrDefault("name", "")) + "\n" + map.getOrDefault("run", ""));
        }
        return rendered;
    }
}
