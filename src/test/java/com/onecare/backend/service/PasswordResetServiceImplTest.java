package com.onecare.backend.service;

import com.onecare.backend.entity.PasswordResetToken;
import com.onecare.backend.entity.User;
import com.onecare.backend.exception.EmailSendException;
import com.onecare.backend.exception.InvalidResetTokenException;
import com.onecare.backend.repository.PasswordResetTokenRepository;
import com.onecare.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceImplTest {

    private static final Pattern RAW_TOKEN_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");
    private static final int EXPIRATION_MINUTES = 30;
    private static final String EMAIL = "doctor1@example.com";

    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailService emailService;

    // Same parameters as SecurityConfig.passwordEncoder() — must stay in sync
    private final PasswordEncoder passwordEncoder = new Argon2PasswordEncoder(16, 32, 1, 65536, 2);

    private PasswordResetServiceImpl passwordResetService;
    private User user;

    @BeforeEach
    void setUp() {
        passwordResetService = new PasswordResetServiceImpl(
                passwordResetTokenRepository,
                userRepository,
                emailService,
                passwordEncoder,
                EXPIRATION_MINUTES);
        user = new User();
        user.setUserId(1L);
        user.setUsername("doctor1");
        user.setEmail(EMAIL);
        user.setPasswordHash("old-hash");
        user.setFailedAttempts(3);
        user.setLockedUntil(LocalDateTime.now().plusMinutes(10));
        user.setPasswordChangeRequired(true);
    }

    // ---------- token issuance (Phase 3) ----------

    @Test
    void issueResetTokenShouldReturnUrlSafeHighEntropyRawToken() {
        String rawToken = passwordResetService.issueResetToken(user);

        assertThat(rawToken).matches(RAW_TOKEN_FORMAT);
    }

    @Test
    void issueResetTokenShouldStoreSha256HashNotRawToken() {
        String rawToken = passwordResetService.issueResetToken(user);

        ArgumentCaptor<PasswordResetToken> captor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(passwordResetTokenRepository).save(captor.capture());
        PasswordResetToken saved = captor.getValue();

        assertThat(saved.getTokenHash()).matches(SHA256_HEX);
        assertThat(saved.getTokenHash()).isNotEqualTo(rawToken);
        assertThat(saved.getTokenHash()).doesNotContain(rawToken);
        assertThat(saved.getUser()).isEqualTo(user);
        assertThat(saved.isUsed()).isFalse();
        assertThat(saved.getUsedAt()).isNull();
    }

    @Test
    void issueResetTokenShouldSetExpiryFromConfigurationNotALiteral() {
        LocalDateTime before = LocalDateTime.now().plusMinutes(EXPIRATION_MINUTES);

        passwordResetService.issueResetToken(user);

        LocalDateTime after = LocalDateTime.now().plusMinutes(EXPIRATION_MINUTES);
        ArgumentCaptor<PasswordResetToken> captor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(passwordResetTokenRepository).save(captor.capture());

        assertThat(captor.getValue().getExpiresAt())
                .isBetween(before.minusSeconds(5), after.plusSeconds(5));
    }

    @Test
    void issueResetTokenShouldInvalidateActiveTokensBeforeSavingNewOne() {
        passwordResetService.issueResetToken(user);

        verify(passwordResetTokenRepository).invalidateActiveTokens(eq(user), any(LocalDateTime.class));
        verify(passwordResetTokenRepository).save(any(PasswordResetToken.class));
        verifyNoMoreInteractions(passwordResetTokenRepository);
    }

    @Test
    void issueResetTokenShouldGenerateDifferentTokenEachTime() {
        String first = passwordResetService.issueResetToken(user);
        String second = passwordResetService.issueResetToken(user);

        assertThat(first).isNotEqualTo(second);
    }

    // ---------- requestPasswordReset (Phase 5) ----------

    @Test
    void requestPasswordResetShouldIssueTokenAndSendEmailForKnownUser() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        passwordResetService.requestPasswordReset(EMAIL);

        verify(passwordResetTokenRepository).invalidateActiveTokens(eq(user), any(LocalDateTime.class));
        verify(passwordResetTokenRepository).save(any(PasswordResetToken.class));
        verify(emailService).sendPasswordResetEmail(eq(EMAIL), anyString());
    }

    @Test
    void requestPasswordResetShouldBeSilentNoOpForUnknownEmail() {
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        passwordResetService.requestPasswordReset("unknown@example.com");

        verify(emailService, never()).sendPasswordResetEmail(anyString(), anyString());
        verify(passwordResetTokenRepository, never()).save(any(PasswordResetToken.class));
    }

    @Test
    void requestPasswordResetShouldHaveSameOutcomeForExistingAndUnknownEmail() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        // Neither call throws — caller cannot distinguish registered vs unknown
        assertThatNoException().isThrownBy(() -> passwordResetService.requestPasswordReset(EMAIL));
        assertThatNoException().isThrownBy(() -> passwordResetService.requestPasswordReset("unknown@example.com"));

        verify(emailService, times(1)).sendPasswordResetEmail(eq(EMAIL), anyString());
        verify(emailService, never()).sendPasswordResetEmail(eq("unknown@example.com"), anyString());
    }

    @Test
    void requestPasswordResetShouldInvalidatePriorActiveTokenOnReRequest() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        passwordResetService.requestPasswordReset(EMAIL);
        passwordResetService.requestPasswordReset(EMAIL);

        verify(passwordResetTokenRepository, times(2))
                .invalidateActiveTokens(eq(user), any(LocalDateTime.class));
        verify(passwordResetTokenRepository, times(2)).save(any(PasswordResetToken.class));
    }

    @Test
    void requestPasswordResetShouldPropagateEmailSendFailure() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        doThrow(new EmailSendException("Failed to send password reset email"))
                .when(emailService).sendPasswordResetEmail(eq(EMAIL), anyString());

        assertThatThrownBy(() -> passwordResetService.requestPasswordReset(EMAIL))
                .isInstanceOf(EmailSendException.class);
    }

    // ---------- resetPassword: success ----------

    @Test
    void resetPasswordWithValidTokenShouldChangePasswordUnlockAndConsumeToken() {
        String rawToken = "valid-raw-token";
        PasswordResetToken token = activeToken(sha256Hex(rawToken));
        when(passwordResetTokenRepository.findByTokenHash(sha256Hex(rawToken)))
                .thenReturn(Optional.of(token));

        passwordResetService.resetPassword(rawToken, "NewSecurePassword123!");

        assertThat(passwordEncoder.matches("NewSecurePassword123!", user.getPasswordHash())).isTrue();
        assertThat(user.getPasswordHash()).isNotEqualTo("NewSecurePassword123!");
        assertThat(user.getFailedAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
        assertThat(user.getPasswordChangeRequired()).isFalse();
        assertThat(token.isUsed()).isTrue();
        assertThat(token.getUsedAt()).isNotNull();

        verify(userRepository).save(user);
        verify(passwordResetTokenRepository).save(token);
    }

    // ---------- resetPassword: rejections ----------

    @Test
    void resetPasswordWithUnknownTokenShouldBeRejected() {
        when(passwordResetTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> passwordResetService.resetPassword("random-token", "NewSecurePassword123!"))
                .isInstanceOf(InvalidResetTokenException.class)
                .hasMessage("Invalid or expired reset token");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void resetPasswordWithExpiredTokenShouldBeRejected() {
        String rawToken = "expired-raw-token";
        PasswordResetToken token = activeToken(sha256Hex(rawToken));
        token.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(passwordResetTokenRepository.findByTokenHash(sha256Hex(rawToken)))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> passwordResetService.resetPassword(rawToken, "NewSecurePassword123!"))
                .isInstanceOf(InvalidResetTokenException.class)
                .hasMessage("Invalid or expired reset token");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void resetPasswordWithReusedTokenShouldBeRejected() {
        String rawToken = "reused-raw-token";
        PasswordResetToken token = activeToken(sha256Hex(rawToken));
        token.setUsed(true);
        token.setUsedAt(LocalDateTime.now().minusMinutes(1));
        when(passwordResetTokenRepository.findByTokenHash(sha256Hex(rawToken)))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> passwordResetService.resetPassword(rawToken, "NewSecurePassword123!"))
                .isInstanceOf(InvalidResetTokenException.class)
                .hasMessage("Invalid or expired reset token");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void resetPasswordShouldGiveSameMessageForUnknownExpiredAndUsedTokens() {
        when(passwordResetTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());
        String unknownMessage = catchMessage("token-a");

        PasswordResetToken expired = activeToken(sha256Hex("token-b"));
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(passwordResetTokenRepository.findByTokenHash(sha256Hex("token-b")))
                .thenReturn(Optional.of(expired));
        String expiredMessage = catchMessage("token-b");

        PasswordResetToken used = activeToken(sha256Hex("token-c"));
        used.setUsed(true);
        when(passwordResetTokenRepository.findByTokenHash(sha256Hex("token-c")))
                .thenReturn(Optional.of(used));
        String usedMessage = catchMessage("token-c");

        assertThat(unknownMessage).isEqualTo(expiredMessage).isEqualTo(usedMessage);
    }

    // ---------- helpers ----------

    private PasswordResetToken activeToken(String tokenHash) {
        PasswordResetToken token = new PasswordResetToken();
        token.setId(10L);
        token.setUser(user);
        token.setTokenHash(tokenHash);
        token.setExpiresAt(LocalDateTime.now().plusMinutes(EXPIRATION_MINUTES));
        token.setUsed(false);
        return token;
    }

    private String catchMessage(String rawToken) {
        try {
            passwordResetService.resetPassword(rawToken, "NewSecurePassword123!");
            throw new AssertionError("expected InvalidResetTokenException");
        } catch (InvalidResetTokenException e) {
            return e.getMessage();
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
