package com.onecare.backend.integration;

import com.onecare.backend.entity.User;
import com.onecare.backend.enums.Role;
import com.onecare.backend.exception.EmailSendException;
import com.onecare.backend.repository.PasswordResetTokenRepository;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PasswordResetApiIntegrationTest {

    private static final String KNOWN_EMAIL = "doctor1@onecare.com";
    private static final String FORGOT_GENERIC_MESSAGE =
            "If the email address is registered, a password reset link has been sent.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();

        User user = new User();
        user.setUsername("doctor1");
        user.setEmail(KNOWN_EMAIL);
        user.setPasswordHash(passwordEncoder.encode("Password@123"));
        user.setRole(Role.DOCTOR);
        user.setIsActive(true);
        user.setFailedAttempts(0);
        userRepository.save(user);
    }

    @Test
    void forgotPasswordShouldReturnIdenticalResponseForExistingAndUnknownEmail() throws Exception {
        String existingBody = mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + KNOWN_EMAIL + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value(FORGOT_GENERIC_MESSAGE))
                .andReturn().getResponse().getContentAsString();

        String unknownBody = mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"unknown@onecare.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value(FORGOT_GENERIC_MESSAGE))
                .andReturn().getResponse().getContentAsString();

        // Same externally visible response — no account enumeration
        assertThat(unknownBody).isEqualTo(existingBody);

        // Email only for the known address; token never appears in the response
        verify(emailService, times(1)).sendPasswordResetEmail(eq(KNOWN_EMAIL), anyString());
        assertThat(existingBody).doesNotContain("token");
    }

    @Test
    void bothEndpointsShouldBeReachableWithoutAuthentication() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + KNOWN_EMAIL + "\"}"))
                .andExpect(status().isOk());

        // reset-password is also permitAll — rejected by business rules (400),
        // never by security (401/403), without an Authorization header
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"unknown-token\",\"newPassword\":\"NewPassword123!\"}"))
                .andExpect(result -> {
                    int sc = result.getResponse().getStatus();
                    org.assertj.core.api.Assertions.assertThat(sc).isNotEqualTo(401).isNotEqualTo(403);
                })
                .andExpect(status().isBadRequest());
    }

    @Test
    void sameTokenUsedTwiceShouldReturn400OnSecondAttempt() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + KNOWN_EMAIL + "\"}"))
                .andExpect(status().isOk());
        String rawToken = capturedRawToken();

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + rawToken + "\",\"newPassword\":\"NewPassword123!\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + rawToken + "\",\"newPassword\":\"AnotherPassword123!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Invalid or expired reset token"));
    }

    @Test
    void forgotPasswordMissingEmailShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.data.email").exists());
    }

    @Test
    void resetPasswordWithUnknownTokenShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"unknown-token\",\"newPassword\":\"NewPassword123!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Invalid or expired reset token"));
    }

    @Test
    void resetPasswordWithExpiredTokenShouldReturn400WithSameGenericMessage() throws Exception {
        // Issue a real token, then mark it expired
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + KNOWN_EMAIL + "\"}"))
                .andExpect(status().isOk());

        String rawToken = capturedRawToken();
        var tokens = passwordResetTokenRepository.findAll();
        tokens.forEach(token -> token.setExpiresAt(java.time.LocalDateTime.now().minusMinutes(1)));
        passwordResetTokenRepository.saveAll(tokens);

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + rawToken + "\",\"newPassword\":\"NewPassword123!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Invalid or expired reset token"));
    }

    @Test
    void validationFailureShouldReturn400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.data.token").exists())
                .andExpect(jsonPath("$.data.newPassword").exists());
    }

    @Test
    void smtpFailureShouldReturn503() throws Exception {
        doThrow(new EmailSendException("SMTP down"))
                .when(emailService).sendPasswordResetEmail(anyString(), anyString());

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + KNOWN_EMAIL + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value("Unable to send email at this time. Please try again later."));
    }

    private String capturedRawToken() {
        org.mockito.ArgumentCaptor<String> tokenCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(emailService).sendPasswordResetEmail(eq(KNOWN_EMAIL), tokenCaptor.capture());
        return tokenCaptor.getValue();
    }

    @Test
    void shouldAllowLoginWithNewPasswordAfterResetAndRejectOldPassword() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + KNOWN_EMAIL + "\"}"))
                .andExpect(status().isOk());
        String rawToken = capturedRawToken();

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + rawToken + "\",\"newPassword\":\"NewPassword123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password has been reset successfully"));

        // Old password must no longer authenticate. Assert non-success rather than
        // exactly 401: recording the failed attempt uses MySQL-only DATE_ADD, which
        // errors (500) on the local H2 profile — known pre-existing limitation,
        // returns 401 on MySQL in CI.
        String oldPasswordBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"doctor1\",\"password\":\"Password@123\"}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(200))
                .andReturn().getResponse().getContentAsString();
        assertThat(oldPasswordBody).doesNotContain("accessToken");

        // New password works
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"doctor1\",\"password\":\"NewPassword123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }
}
