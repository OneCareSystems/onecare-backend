package com.onecare.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.Role;
import com.onecare.backend.config.TestRedisConfiguration;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.security.JwtService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.onecare.backend.security.TokenBlacklistService;
import java.util.concurrent.TimeUnit;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// Keep this class hashing at true production strength so the hardcoded
// m=65536,t=2,p=1 assertion below stays meaningful; other test classes use the
// cheaper Argon2 cost from src/test/resources/application.properties.
@org.springframework.test.context.TestPropertySource(properties = {
        "app.security.argon2.parallelism=1",
        "app.security.argon2.memory-kb=65536",
        "app.security.argon2.iterations=2"
})
class JwtAuthenticationIntegrationTest {
        @MockitoBean
private JavaMailSender javaMailSender;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private TokenBlacklistService tokenBlacklistService;

    @Autowired
    private TestRedisConfiguration testRedisConfiguration;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @BeforeEach
    void setupUser() {
        testRedisConfiguration.clearInMemoryStore();

        User user = userRepository.findByUsername("doctor1").orElseGet(User::new);
        user.setUsername("doctor1");
        user.setEmail("doctor1@onecare.com");
        user.setPasswordHash(passwordEncoder.encode("Password@123"));
        user.setRole(Role.DOCTOR);
        user.setIsActive(true);
        user.setFailedAttempts(0);
        user.setLockedUntil(null);

        userRepository.save(user);
    }

    @Test
    void passwordEncoderUsesConfiguredArgon2idParameters() {
            String rawPassword = "Password@123";

            String encodedPassword = passwordEncoder.encode(rawPassword);

            assertThat(encodedPassword)
                            .startsWith("$argon2id$v=19$m=65536,t=2,p=1$");

            assertThat(passwordEncoder.matches(rawPassword, encodedPassword))
                            .isTrue();

            assertThat(passwordEncoder.matches("WrongPassword@123", encodedPassword))
                            .isFalse();
    }

    @Test
    void validLoginReturnsJwtWithRequiredClaims() throws Exception {
        String responseBody = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"doctor1","password":"Password@123"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresIn").value(1800000))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode body = objectMapper.readTree(responseBody);
        JsonNode data = body.get("data");

        assertThat(data).isNotNull();
        assertThat(data.get("accessToken")).isNotNull();

        String accessToken = data.get("accessToken").asText();

        var claims = jwtService.validateAndExtract(accessToken);

        assertThat(claims.get("userId")).isNotNull();
        assertThat(claims.get("role")).isEqualTo("DOCTOR");
        assertThat(claims.getExpiration()).isAfter(new Date());
    }

    // login returns the username and keeps every existing field
    @Test
    void loginReturnsUsernameAndPreservesExistingFields() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"doctor1","password":"Password@123"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("doctor1"))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresIn").value(1800000))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.role").value("DOCTOR"))
                .andExpect(jsonPath("$.data.redirectUrl").value("/doctor/dashboard"));
    }

    @Test
    void invalidCredentialsReturnUnauthorized() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"doctor1","password":"wrong-password"}
                        """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/secure/ping"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/secure/ping")
                .header("Authorization", "Bearer invalid.token.value"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        SecretKey key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(jwtSecret));
        Date now = Date.from(Instant.now());
        Date expired = Date.from(Instant.now().minusSeconds(60));

        String expiredToken = Jwts.builder()
                .subject("doctor1")
                .claims(Map.of("type", "access", "role", "DOCTOR", "userId", 1L))
                .issuedAt(now)
                .expiration(expired)
                .signWith(key)
                .compact();

        mockMvc.perform(get("/api/secure/ping")
                .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutBlacklistsTheToken() throws Exception {
        String responseBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"doctor1","password":"Password@123"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String token = objectMapper.readTree(responseBody).get("data").get("accessToken").asText();

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Logout successful"));

        assertThat(tokenBlacklistService.isBlacklisted(token)).isTrue();
        String redisKey = tokenBlacklistService.buildKey(token);
        assertThat(redisTemplate.hasKey(redisKey)).isTrue();
        Long ttl = redisTemplate.getExpire(redisKey, TimeUnit.MILLISECONDS);
        assertThat(ttl).isNotNull().isPositive();
    }

    @Test
    void blacklistedTokenCannotAccessProtectedApis() throws Exception {
        String token = jwtService.issueAccessToken(1L, "doctor1", "DOCTOR");
        tokenBlacklistService.blacklistToken(token);

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void redisBlacklistEntryExpiresCorrectly() throws Exception {
        String key = "blacklist:token:test-expire-key";
        redisTemplate.opsForValue().set(key, "revoked", 80, TimeUnit.MILLISECONDS);
        assertThat(redisTemplate.hasKey(key)).isTrue();

        Thread.sleep(120);

        assertThat(redisTemplate.hasKey(key)).isFalse();
    }

    @Test
    void oldTokenReuseAfterLogoutIsRejected() throws Exception {
        String responseBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"doctor1","password":"Password@123"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String accessToken = objectMapper.readTree(responseBody).get("data").get("accessToken").asText();

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedLogoutReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithRefreshTokenBlocksFutureRefresh() throws Exception {
        String responseBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"doctor1","password":"Password@123"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var data = objectMapper.readTree(responseBody).get("data");
        String accessToken = data.get("accessToken").asText();
        String refreshToken = data.get("refreshToken").asText();

        // Logout revokes both tokens when the client sends the refresh token.
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("refreshToken", refreshToken))))
                .andExpect(status().isOk());

        assertThat(tokenBlacklistService.isBlacklisted(accessToken)).isTrue();
        assertThat(tokenBlacklistService.isBlacklisted(refreshToken)).isTrue();

        // The revoked refresh token must not mint a new access token (SDS 2.5.1).
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithoutBodyKeepsWorkingAndOnlyRevokesAccessToken() throws Exception {
        // Backwards compatibility: existing clients send no body; the refresh
        // token stays valid in that case (documented trade-off), but logout
        // itself must still succeed exactly as before.
        String responseBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"doctor1","password":"Password@123"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var data = objectMapper.readTree(responseBody).get("data");
        String accessToken = data.get("accessToken").asText();
        String refreshToken = data.get("refreshToken").asText();

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logout successful"));

        assertThat(tokenBlacklistService.isBlacklisted(accessToken)).isTrue();
        assertThat(tokenBlacklistService.isBlacklisted(refreshToken)).isFalse();

        // Without the blacklist the refresh token still works (no-body path).
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }
}