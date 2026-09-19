package com.onecare.backend.service;

import com.onecare.backend.entity.User;
import com.onecare.backend.enums.Role;
import com.onecare.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class UserServiceImplTest {

    private UserServiceImpl userService;
    private User user;

    @BeforeEach
    void setUp() {
        UserRepository userRepository = mock(UserRepository.class);

        PasswordEncoder passwordEncoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

        userService = new UserServiceImpl(
                userRepository,
                passwordEncoder
        );

        user = new User();
        user.setUserId(3L);
        user.setUsername("doctor1");
        user.setRole(Role.DOCTOR);
        user.setFailedAttempts(0);
        user.setAccountLocked(false);
        user.setLockedUntil(null);
        user.setIsActive(true);
    }

    @Test
    void passwordHashingShouldUseArgon2idAndVerifyCorrectPassword() {

        PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

        String rawPassword = "Password@123";

        String hashedPassword = encoder.encode(rawPassword);

        assertNotEquals(rawPassword, hashedPassword);

        assertTrue(
                hashedPassword.startsWith("$argon2id$"),
                "Password hash should use Argon2id"
        );

        assertTrue(
                encoder.matches(rawPassword, hashedPassword),
                "Correct password should match Argon2id hash"
        );

        assertFalse(
                encoder.matches("WrongPassword@123", hashedPassword),
                "Incorrect password should not match Argon2id hash"
        );
    }

    @Test
    void failedAttemptShouldIncrementCounter() {

        user.setFailedAttempts(0);

        boolean locked = userService.recordFailedAttempt(user);

        assertFalse(locked);
        assertEquals(1, user.getFailedAttempts());
        assertFalse(user.getAccountLocked());
    }

    @Test
    void accountShouldLockAfterTenFailedAttempts() {

        user.setFailedAttempts(9);
        user.setAccountLocked(false);

        boolean locked = userService.recordFailedAttempt(user);

        assertTrue(locked);
        assertEquals(10, user.getFailedAttempts());
        assertTrue(user.getAccountLocked());
        assertNull(user.getLockedUntil());
    }

    @Test
    void successfulLoginShouldResetFailedAttemptsAndUnlockAccount() {

        user.setFailedAttempts(7);
        user.setAccountLocked(true);
        user.setLockedUntil(null);

        userService.recordSuccessfulLogin(user);

        assertEquals(0, user.getFailedAttempts());
        assertFalse(user.getAccountLocked());
        assertNull(user.getLockedUntil());
        assertNotNull(user.getLastLogin());
    }
}

