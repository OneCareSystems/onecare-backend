package com.onecare.backend.service;

import com.onecare.backend.dto.response.UserResponse;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.Role;
import com.onecare.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserServiceImplTest {

    private UserServiceImpl userService;
    private UserRepository userRepository;
    private User user;

    @BeforeEach
    void setUp() {

        userRepository = mock(UserRepository.class);

        userService = new UserServiceImpl(userRepository);

        user = new User();
        user.setUserId(3L);
        user.setUsername("doctor1");
        user.setRole(Role.DOCTOR);
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        user.setIsActive(true);

        when(userRepository.findById(3L))
                .thenReturn(Optional.of(user));
    }

    @Test
    void passwordHashingShouldUseArgon2idAndVerifyCorrectPassword() {

        PasswordEncoder encoder =
                Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

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
    }

    @Test
    void accountShouldLockAfterFiveFailedAttempts() {

        user.setFailedAttempts(4);

        LocalDateTime before =
                LocalDateTime.now().plusMinutes(15);

        boolean locked =
                userService.recordFailedAttempt(user);

        LocalDateTime after =
                LocalDateTime.now().plusMinutes(15);

        assertTrue(locked);
        assertEquals(5, user.getFailedAttempts());
        assertNotNull(user.getLockedUntil());

        assertFalse(
                user.getLockedUntil().isBefore(before)
        );

        assertFalse(
                user.getLockedUntil().isAfter(after)
        );
    }

    @Test
    void successfulLoginShouldResetFailedAttemptsAndUnlockAccount() {

        user.setFailedAttempts(7);
        user.setLockedUntil(
                LocalDateTime.now().plusMinutes(15)
        );

        userService.recordSuccessfulLogin(user);

        assertEquals(0, user.getFailedAttempts());
        assertNull(user.getLockedUntil());
        assertNotNull(user.getLastLogin());
    }

    @Test
    void unlockAccountShouldResetFailedAttemptsAndLockedUntil() {

        user.setFailedAttempts(5);
        user.setLockedUntil(
                LocalDateTime.now().plusMinutes(15)
        );

        UserResponse response =
                userService.unlockAccount(3L);

        assertEquals(0, user.getFailedAttempts());
        assertNull(user.getLockedUntil());

        assertNotNull(response);
        assertEquals(3L, response.userId());
        assertEquals("doctor1", response.username());
        assertEquals(0, response.failedAttempts());
        assertNull(response.lockedUntil());
    }
}