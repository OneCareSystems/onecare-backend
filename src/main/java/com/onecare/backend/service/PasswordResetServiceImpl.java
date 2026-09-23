package com.onecare.backend.service;

import com.onecare.backend.entity.PasswordResetToken;
import com.onecare.backend.entity.User;
import com.onecare.backend.repository.PasswordResetTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

@Service
@Transactional
public class PasswordResetServiceImpl implements PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetServiceImpl.class);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final int tokenExpirationMinutes;

    public PasswordResetServiceImpl(
            PasswordResetTokenRepository passwordResetTokenRepository,
            @Value("${password-reset.token-expiration-minutes}") int tokenExpirationMinutes) {
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.tokenExpirationMinutes = tokenExpirationMinutes;
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

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
