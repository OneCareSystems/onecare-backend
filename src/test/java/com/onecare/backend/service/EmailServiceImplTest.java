package com.onecare.backend.service;

import com.onecare.backend.exception.EmailSendException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EmailServiceImplTest {

    private static final String FRONTEND_BASE_URL = "http://localhost:3000";
    private static final int EXPIRATION_MINUTES = 30;
    private static final String RAW_TOKEN = "raw-token-abc123";
    private static final String RECIPIENT = "doctor1@example.com";

    @Mock
    private JavaMailSender javaMailSender;

    private EmailServiceImpl emailService;

    @BeforeEach
    void setUp() {
        emailService = new EmailServiceImpl(javaMailSender, FRONTEND_BASE_URL, EXPIRATION_MINUTES);
    }

    @Test
    void sendPasswordResetEmailShouldSendCorrectRecipientSubjectAndLink() {
        emailService.sendPasswordResetEmail(RECIPIENT, RAW_TOKEN);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();

        assertThat(sent.getTo()).containsExactly(RECIPIENT);
        assertThat(sent.getSubject()).isEqualTo("OneCare - Reset your password");

        String body = sent.getText();
        assertThat(body).contains(FRONTEND_BASE_URL + "/reset-password?token=" + RAW_TOKEN);
        assertThat(body).contains("expires in 30 minutes");
        assertThat(body).contains("If you did not request this");
        assertThat(body).doesNotContain("password_hash");
        assertThat(body).doesNotContain("tokenHash");
    }

    @Test
    void sendPasswordResetEmailShouldHandleBaseUrlWithTrailingSlash() {
        EmailServiceImpl serviceWithSlash =
                new EmailServiceImpl(javaMailSender, "http://localhost:3000/", EXPIRATION_MINUTES);

        serviceWithSlash.sendPasswordResetEmail(RECIPIENT, RAW_TOKEN);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender).send(captor.capture());
        assertThat(captor.getValue().getText())
                .contains("http://localhost:3000/reset-password?token=" + RAW_TOKEN)
                .doesNotContain("3000//reset-password");
    }

    @Test
    void sendPasswordResetEmailShouldWrapMailExceptionInEmailSendException() {
        doThrow(new MailSendException("SMTP down"))
                .when(javaMailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> emailService.sendPasswordResetEmail(RECIPIENT, RAW_TOKEN))
                .isInstanceOf(EmailSendException.class)
                .hasMessage("Failed to send password reset email")
                .hasCauseInstanceOf(MailSendException.class);
    }
}
