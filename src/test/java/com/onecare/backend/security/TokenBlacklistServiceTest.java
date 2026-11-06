package com.onecare.backend.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenBlacklistServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private JwtService jwtService;

    private TokenBlacklistService tokenBlacklistService;

    @BeforeEach
    void setUp() {
        tokenBlacklistService = new TokenBlacklistService(redisTemplate, jwtService);
    }

    @Test
    void blacklistTokenShouldStoreHashedTokenWithRemainingTtl() {
        String token = "sample.jwt.token";
        long remainingTtl = 5000L;

        when(jwtService.getRemainingTtlMillis(token)).thenReturn(remainingTtl);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        tokenBlacklistService.blacklistToken(token);

        String expectedKey = tokenBlacklistService.buildKey(token);
        assertThat(expectedKey).startsWith(TokenBlacklistService.BLACKLIST_KEY_PREFIX);
        assertThat(expectedKey).isNotEqualTo(TokenBlacklistService.BLACKLIST_KEY_PREFIX + token);

        verify(valueOperations).set(eq(expectedKey), eq("revoked"), eq(remainingTtl), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void blacklistTokenShouldNotStoreIfAlreadyExpired() {
        String token = "sample.jwt.token";

        when(jwtService.getRemainingTtlMillis(token)).thenReturn(0L);

        tokenBlacklistService.blacklistToken(token);

        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void isBlacklistedShouldReturnTrueWhenKeyPresent() {
        String token = "sample.jwt.token";
        String key = tokenBlacklistService.buildKey(token);

        when(redisTemplate.hasKey(key)).thenReturn(Boolean.TRUE);

        boolean result = tokenBlacklistService.isBlacklisted(token);

        assertThat(result).isTrue();
        verify(redisTemplate).hasKey(key);
    }

    @Test
    void isBlacklistedShouldReturnFalseWhenKeyAbsent() {
        String token = "sample.jwt.token";
        String key = tokenBlacklistService.buildKey(token);

        when(redisTemplate.hasKey(key)).thenReturn(Boolean.FALSE);

        boolean result = tokenBlacklistService.isBlacklisted(token);

        assertThat(result).isFalse();
        verify(redisTemplate).hasKey(key);
    }
}
