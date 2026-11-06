package com.onecare.backend.service;

import com.onecare.backend.exception.EmailSendException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailServiceImpl implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailServiceImpl.class);

    private final JavaMailSender javaMailSender;
    private final String frontendBaseUrl;
    private final int tokenExpirationMinutes;

    public EmailServiceImpl(
            JavaMailSender javaMailSender,
            @Value("${password-reset.frontend-base-url}") String frontendBaseUrl,
            @Value("${password-reset.token-expiration-minutes}") int tokenExpirationMinutes) {
        this.javaMailSender = javaMailSender;
        this.frontendBaseUrl = stripTrailingSlash(frontendBaseUrl);
        this.tokenExpirationMinutes = tokenExpirationMinutes;
    }

    @Override
    public void sendPasswordResetEmail(String toEmail, String rawToken) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(toEmail);
            message.setSubject("OneCare - Reset your password");
            message.setText(buildBody(rawToken));
            javaMailSender.send(message);
            log.info("Password reset email sent | to={}", toEmail);
        } catch (MailException e) {
            log.error("Failed to send password reset email | to={}", toEmail, e);
            throw new EmailSendException("Failed to send password reset email", e);
        }
    }

    private String buildBody(String rawToken) {
        String resetLink = frontendBaseUrl + "/reset-password?token=" + rawToken;
        return """
                You requested a password reset.

                Click the following link to reset your password:

                %s

                This link expires in %d minutes.

                If you did not request this, you can ignore this email.
                """.formatted(resetLink, tokenExpirationMinutes);
    }

    private static String stripTrailingSlash(String url) {
        if (url != null && url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}
