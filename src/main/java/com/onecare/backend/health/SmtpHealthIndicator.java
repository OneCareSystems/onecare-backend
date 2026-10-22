package com.onecare.backend.health;

import com.onecare.backend.config.HealthConfig;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * SMTP reachability probe.
 *
 * <p>The SMTP gateway is NOT part of the startup gate: when it is unreachable
 * this indicator reports {@code DEGRADED} (a custom status ordered below UP,
 * see {@link com.onecare.backend.config.HealthConfig}) instead of DOWN. The
 * health endpoint therefore still answers HTTP 200 and the container stays
 * healthy in docker compose, while the body makes the degradation visible.
 * Core clinic flows (appointments, billing, prescriptions) do not depend on
 * outbound email, so they keep working.
 *
 * <p>Only MySQL and Redis gate startup / container health.
 *
 * <p>The probe opens and closes a real SMTP connection. It never sends mail
 * and never logs credentials — only the exception class and the host.
 */
@Component("smtp")
public class SmtpHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(SmtpHealthIndicator.class);

    private final ObjectProvider<JavaMailSender> javaMailSender;
    private final long connectTimeoutMillis;

    public SmtpHealthIndicator(
            ObjectProvider<JavaMailSender> javaMailSender,
            @Value("${management.health.smtp.connect-timeout-ms:3000}") long connectTimeoutMillis) {
        this.javaMailSender = javaMailSender;
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    @Override
    public Health health() {
        JavaMailSender sender = javaMailSender.getIfAvailable();
        if (sender == null) {
            // No mail sender bean at all (spring.mail.host unset) — not a crash,
            // password-reset emails simply cannot go out until it is configured.
            return Health.status(HealthConfig.DEGRADED)
                    .withDetail("reason", "SMTP is not configured (spring.mail.host is missing)")
                    .build();
        }
        if (!(sender instanceof JavaMailSenderImpl impl)) {
            // Unexpected sender type (e.g. a test mock); cannot probe it.
            return Health.up()
                    .withDetail("reason", "SMTP sender is not probeable, skipping reachability check")
                    .build();
        }

        String host = impl.getHost();
        int port = impl.getPort();
        if (host == null || host.isBlank()) {
            return Health.status(HealthConfig.DEGRADED)
                    .withDetail("reason", "SMTP is not configured (spring.mail.host is empty)")
                    .build();
        }

        try {
            probe(host, port, impl.getUsername(), impl.getPassword());
            return Health.up()
                    .withDetail("host", host)
                    .withDetail("port", port)
                    .build();
        } catch (Exception e) {
            // Sanitized on purpose: class name + host only. The message of a
            // MailSendException can embed server responses; never the password.
            log.warn("SMTP health probe failed | host={} | error={}", host, e.getClass().getSimpleName());
            return Health.status(HealthConfig.DEGRADED)
                    .withDetail("host", host)
                    .withDetail("port", port)
                    .withDetail("reason", "SMTP gateway unreachable")
                    .withDetail("error", e.getClass().getSimpleName())
                    .build();
        }
    }

    /** Opens the SMTP conversation (CONNECT/EHLO/AUTH) then quits. No mail is sent. */
    private void probe(String host, int port, String username, String password) throws MessagingException {
        Properties props = new Properties();
        props.setProperty("mail.smtp.connectiontimeout", String.valueOf(connectTimeoutMillis));
        props.setProperty("mail.smtp.timeout", String.valueOf(connectTimeoutMillis));
        props.setProperty("mail.smtp.writetimeout", String.valueOf(connectTimeoutMillis));
        props.setProperty("mail.smtp.auth", String.valueOf(username != null && !username.isBlank()));

        Transport transport = Session.getInstance(props).getTransport("smtp");
        try {
            if (username != null && !username.isBlank()) {
                transport.connect(host, port, username, password);
            } else {
                transport.connect(host, port, null, null);
            }
        } finally {
            if (transport.isConnected()) {
                transport.close();
            }
        }
    }
}
