package com.onecare.backend.service;

import com.onecare.backend.entity.PasswordResetToken;
import com.onecare.backend.entity.User;
import com.onecare.backend.exception.InvalidResetTokenException;
import com.onecare.backend.repository.PasswordResetTokenRepository;
import com.onecare.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

@Service
@Transactional
public class PasswordResetServiceImpl implements PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetServiceImpl.class);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final int tokenExpirationMinutes;

    public PasswordResetServiceImpl(
            PasswordResetTokenRepository passwordResetTokenRepository,
            UserRepository userRepository,
            EmailService emailService,
            PasswordEncoder passwordEncoder,
            @Value("${password-reset.token-expiration-minutes}") int tokenExpirationMinutes) {
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
        this.tokenExpirationMinutes = tokenExpirationMinutes;
    }

    @Override
    public void requestPasswordReset(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            String rawToken = issueResetToken(user);
            emailService.sendPasswordResetEmail(user.getEmail(), rawToken);
        });
        // Unknown email: silent no-op — identical external outcome (no enumeration)
    }

    @Override
    public void resetPassword(String rawToken, String newPassword) {
        String tokenHash = hashToken(rawToken);

        PasswordResetToken token = passwordResetTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(PasswordResetServiceImpl::invalidToken);

        if (token.isUsed()) {
            log.warn("Password reset rejected | userId={} | reason=TOKEN_USED",
                    token.getUser().getUserId());
            throw invalidToken();
        }
        if (token.getExpiresAt().isBefore(LocalDateTime.now())) {
            log.warn("Password reset rejected | userId={} | reason=TOKEN_EXPIRED",
                    token.getUser().getUserId());
            throw invalidToken();
        }

        User user = token.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        user.setPasswordChangeRequired(false);

        token.setUsed(true);
        token.setUsedAt(LocalDateTime.now());

        userRepository.save(user);
        passwordResetTokenRepository.save(token);

        log.info("Password reset completed | userId={}", user.getUserId());
    }

    @Override
    public String issueResetToken(User user) {
        LocalDateTime now = LocalDateTime.now();

        // Only one active link per user: invalidate previous unused tokens first
        passwordResetTokenRepository.invalidateActiveTokens(user, now);

        String rawToken = generateRawToken();

        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setTokenHash(hashToken(rawToken));
        token.setExpiresAt(now.plusMinutes(tokenExpirationMinutes));
        token.setUsed(false);
        passwordResetTokenRepository.save(token);

        log.info("Password reset token issued | userId={} | expiresAt={}",
                user.getUserId(), token.getExpiresAt());

        return rawToken;
    }

    private static InvalidResetTokenException invalidToken() {
        return new InvalidResetTokenException("Invalid or expired reset token");
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
