package com.onecare.backend.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

@Service
public class TokenBlacklistService {

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklistService.class);
    public static final String BLACKLIST_KEY_PREFIX = "blacklist:token:";

    private final StringRedisTemplate redisTemplate;
    private final JwtService jwtService;

    public TokenBlacklistService(StringRedisTemplate redisTemplate, JwtService jwtService) {
        this.redisTemplate = redisTemplate;
        this.jwtService = jwtService;
    }

    public void blacklistToken(String token) {
        long remainingTtl = jwtService.getRemainingTtlMillis(token);
        if (remainingTtl > 0) {
            String key = buildKey(token);
            redisTemplate.opsForValue().set(key, "revoked", remainingTtl, TimeUnit.MILLISECONDS);
            log.info("Token blacklisted with TTL {} ms | key={}", remainingTtl, key);
        } else {
            log.warn("Attempted to blacklist an already expired token");
        }
    }

    public boolean isBlacklisted(String token) {
        String key = buildKey(token);
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    public String buildKey(String token) {
        return BLACKLIST_KEY_PREFIX + hashToken(token);
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
