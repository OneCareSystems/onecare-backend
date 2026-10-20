package com.onecare.backend.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditSanitizerTest {

    @Test
    void nullOrBlankTextPassesThrough() {
        assertThat(AuditSanitizer.sanitize(null)).isNull();
        assertThat(AuditSanitizer.sanitize("")).isEmpty();
        assertThat(AuditSanitizer.sanitize("   ")).isEqualTo("   ");
    }

    @Test
    void redactsPasswordStyleKeyValuePairs() {
        String sanitized = AuditSanitizer.sanitize(
                "login attempt username=bob password=Password@123 rejected");

        assertThat(sanitized)
                .doesNotContain("Password@123")
                .contains("username=bob")
                .contains("password=[REDACTED]");
    }

    @Test
    void redactsSecretTokenAndCredentialPairs_caseInsensitiveAndQuoted() {
        assertThat(AuditSanitizer.sanitize("body {\"secret\": \"s3cr3t\"}"))
                .doesNotContain("s3cr3t")
                .contains("secret=[REDACTED]");

        assertThat(AuditSanitizer.sanitize("reset token=abc123 used"))
                .doesNotContain("abc123")
                .contains("token=[REDACTED]");

        assertThat(AuditSanitizer.sanitize("header authorization:Bearerabc123"))
                .doesNotContain("Bearerabc123")
                .contains("authorization=[REDACTED]");
    }

    @Test
    void redactsJwtShapedStrings() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJib2IiLCJyb2xlIjoiQURNSU4ifQ.dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

        assertThat(AuditSanitizer.sanitize("parsed subject from token " + jwt))
                .doesNotContain(jwt)
                .contains("[REDACTED]");
    }

    @Test
    void leavesBenignDescriptionUntouched() {
        String benign = "PATIENT_CREATE outcome=SUCCESS target=PatientServiceImpl.createPatient";

        assertThat(AuditSanitizer.sanitize(benign)).isEqualTo(benign);
    }
}
