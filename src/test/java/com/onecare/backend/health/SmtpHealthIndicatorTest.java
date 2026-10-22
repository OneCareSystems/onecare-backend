package com.onecare.backend.health;

import com.onecare.backend.config.HealthConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AC4 (unit): an unreachable or unconfigured SMTP gateway must report DEGRADED,
 * never DOWN — the stack stays up and only the email integration degrades.
 */
class SmtpHealthIndicatorTest {

    private static final long TIMEOUT_MS = 300;

    @Test
    void unreachableSmtpReportsDegradedNotDown() {
        // Port 1 is reserved and never has an SMTP listener: connect is refused.
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("127.0.0.1");
        sender.setPort(1);
        sender.setUsername("probe-user");
        sender.setPassword("probe-password");

        Health health = indicatorFor(sender).health();

        assertThat(health.getStatus()).isEqualTo(HealthConfig.DEGRADED);
        assertThat(health.getStatus()).isNotEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("host", "127.0.0.1")
                .containsEntry("reason", "SMTP gateway unreachable");
        // AC2/AC3 spirit: probe output must never leak credentials.
        assertThat(health.getDetails().toString())
                .doesNotContain("probe-password")
                .doesNotContain("probe-user");
    }

    @Test
    void unconfiguredHostReportsDegraded() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("");
        sender.setPort(587);

        Health health = indicatorFor(sender).health();

        assertThat(health.getStatus()).isEqualTo(HealthConfig.DEGRADED);
        assertThat(health.getDetails().get("reason").toString()).contains("not configured");
    }

    @Test
    void missingMailSenderBeanReportsDegraded() {
        Health health = indicatorFor(null).health();

        assertThat(health.getStatus()).isEqualTo(HealthConfig.DEGRADED);
    }

    @Test
    void nonProbeableSenderIsSkippedAsUp() {
        // A test double / non-impl sender cannot be probed; do not cry wolf.
        JavaMailSender mockSender = mock(JavaMailSender.class);

        Health health = indicatorFor(mockSender).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @SuppressWarnings("unchecked")
    private SmtpHealthIndicator indicatorFor(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        return new SmtpHealthIndicator(provider, TIMEOUT_MS);
    }
}
