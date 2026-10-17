package com.onecare.backend.ci;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses GitHub Actions workflow files and reports every credential-looking key
 * whose value is not sourced from GitHub Secrets .
 *
 * <p>This is the single implementation used by both sides of the gate:</p>
 * <ul>
 *   <li>{@link CiWorkflowConfigurationTest#ac3_allCredentialsComeFromGitHubSecrets()}
 *       asserts the real workflow files produce no violations;</li>
 *   <li>{@link SecurityScanGateTest#injectedPlaintextCredential_isReportedByTheGuard()}
 *       feeds an injected plaintext credential through the same code path and
 *       asserts it is reported (negative test for AC2/AC3).</li>
 * </ul>
 */
public final class WorkflowSecretScanner {

    /** Key fragments that make a YAML key credential-shaped. */
    private static final Set<String> CREDENTIAL_TOKENS = Set.of(
            "password", "passwd", "secret", "token", "username", "user",
            "credential", "apikey", "key", "access", "private");

    /**
     * Key fragments that only look like credentials but are plain settings
     * (token expiries in milliseconds/minutes, cache TTLs, ...).
     */
    private static final Set<String> NON_CREDENTIAL_TOKENS = Set.of(
            "expiry", "expiration", "expire", "ttl", "timeout",
            "duration", "interval", "max", "limit");

    private WorkflowSecretScanner() {
    }

    /**
     * Returns one human-readable violation per credential-shaped YAML key that
     * does not take its value from GitHub Secrets.
     */
    public static List<String> findCredentialViolations(String workflowYaml) {
        List<String> violations = new ArrayList<>();
        String[] lines = workflowYaml.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String rawKey = line.substring(0, colon).trim();
            if (rawKey.isEmpty() || rawKey.contains(" ") || rawKey.startsWith("#")) {
                continue; // not a simple `key: value` mapping entry
            }
            String value = line.substring(colon + 1).trim();
            if (!isCredentialKey(rawKey)) {
                continue;
            }
            if (value.contains("secrets.")) {
                continue; // AC3: value comes from a GitHub Secret
            }
            violations.add(String.format(
                    "line %d: credential key '%s' is not sourced from GitHub Secrets (value: %s)",
                    i + 1, rawKey, value));
        }
        return violations;
    }

    /**
     * True when the workflow reads at least one credential from the GitHub
     * {@code secrets} context - the AC3-compliant way of supplying credentials.
     */
    public static boolean containsSecretsContext(String workflowYaml) {
        return workflowYaml.contains("${{ secrets.");
    }

    /** True when the YAML key denotes a credential (password, token, api key, ...). */
    static boolean isCredentialKey(String key) {
        List<String> tokens = tokenize(key);
        if (tokens.stream().anyMatch(NON_CREDENTIAL_TOKENS::contains)) {
            return false;
        }
        if (tokens.size() == 1 && tokens.get(0).equals("key")) {
            // Bare `key:` is a generic map name (e.g. the actions/cache key),
            // not a credential; credential-shaped names are `api_key`,
            // `secret_key`, `private_key`, ...
            return false;
        }
        boolean apiStyle = tokens.contains("api") && tokens.contains("key");
        boolean tokenStyle = tokens.stream().anyMatch(CREDENTIAL_TOKENS::contains);
        return apiStyle || tokenStyle;
    }

    private static List<String> tokenize(String key) {
        List<String> tokens = new ArrayList<>();
        for (String part : key.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (!part.isBlank()) {
                tokens.add(part);
            }
        }
        return tokens;
    }
}
