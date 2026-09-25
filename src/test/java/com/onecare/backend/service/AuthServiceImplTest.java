package com.onecare.backend.service;

import com.onecare.backend.dto.request.LoginRequest;
import com.onecare.backend.dto.request.RefreshRequest;
import com.onecare.backend.dto.response.AuthResponse;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.Role;
import com.onecare.backend.exception.AccountLockedException;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.security.AppUserDetailsService;
import com.onecare.backend.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private AppUserDetailsService userDetailsService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtService jwtService;

    @Mock
    private UserService userService;

    @Mock
    private PasswordResetService passwordResetService;

    @Mock
    private Authentication authentication;

    private AuthServiceImpl authService;

    private User user;

    @BeforeEach
    void setUp() {

        authService = new AuthServiceImpl(
                authenticationManager,
                userDetailsService,
                userRepository,
                jwtService,
                userService,
                passwordResetService
        );

        user = new User();
        user.setUserId(3L);
        user.setUsername("doctor1");
        user.setRole(Role.DOCTOR);
        user.setIsActive(true);
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
    }

    @Test
    void loginShouldSucceedWithValidCredentials() {

        LoginRequest request = new LoginRequest("doctor1", "Password@123");

        when(userRepository.findByUsername("doctor1")).thenReturn(Optional.of(user));

        when(userService.isAccountLocked(user)).thenReturn(false);

        when(authenticationManager.authenticate(
                any( UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);

        when(jwtService.issueAccessToken(
                3L,
                "doctor1",
                "DOCTOR")).thenReturn("access-token");

        when(jwtService.issueRefreshToken("doctor1")).thenReturn("refresh-token");

        when(jwtService.getAccessTokenExpiry()).thenReturn(900L);

        AuthResponse response = authService.login(request);

        assertNotNull(response);

        verify(authenticationManager).authenticate(any(
                UsernamePasswordAuthenticationToken.class));

        verify(userService).recordSuccessfulLogin(user);

        verify(jwtService).issueAccessToken(
                3L,
                "doctor1",
                "DOCTOR");

        verify(jwtService).issueRefreshToken(
                "doctor1");
    }

    @Test
    void loginShouldIncrementFailedAttemptsWhenPasswordIsWrong() {

        LoginRequest request =
                new LoginRequest("doctor1", "WrongPassword");

        when(userRepository.findByUsername("doctor1")).thenReturn(Optional.of(user));

        when(userService.isAccountLocked(user)).thenReturn(false);

        when(authenticationManager.authenticate(any(
                UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Invalid username or password"));

        when(userService.recordFailedAttempt(user)).thenReturn(false);

        assertThrows(
                BadCredentialsException.class,
                () -> authService.login(request)
        );

        verify(userService).recordFailedAttempt(user);

        verify(userService, never()).recordSuccessfulLogin(user);

        verify(jwtService, never()).issueAccessToken(anyLong(), anyString(), anyString());
    }

    @Test
    void loginShouldLockAccountAfterMaximumFailedAttempts() {

        LoginRequest request =
                new LoginRequest("doctor1", "WrongPassword");

        when(userRepository.findByUsername("doctor1")).thenReturn(Optional.of(user));

        when(userService.isAccountLocked(user)).thenReturn(false);

        when(authenticationManager.authenticate(any(
                UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Invalid username or password"));

        when(userService.recordFailedAttempt(user)).thenReturn(true);

        assertThrows(
                AccountLockedException.class,
                () -> authService.login(request)
        );

        verify(userService).recordFailedAttempt(user);

        verify(userService, never()).recordSuccessfulLogin(user);

        verify(jwtService, never()).issueAccessToken(anyLong(), anyString(), anyString());
    }

    @Test
    void loginShouldRejectAlreadyLockedAccount() {

        LoginRequest request =
                new LoginRequest("doctor1", "Password@123");

        when(userRepository.findByUsername("doctor1")).thenReturn(Optional.of(user));

        when(userService.isAccountLocked(user)).thenReturn(true);

        assertThrows(
                AccountLockedException.class,
                () -> authService.login(request)
        );

        verify(authenticationManager, never()).authenticate(any(UsernamePasswordAuthenticationToken.class));

        verify(userService, never()).recordSuccessfulLogin(user);

        verify(userService, never()).recordFailedAttempt(user);
    }

    @Test
    void loginShouldResetFailedAttemptsAfterSuccessfulAuthentication() {

        user.setFailedAttempts(7);

        LoginRequest request =
                new LoginRequest("doctor1", "Password@123");

        when(userRepository.findByUsername("doctor1")).thenReturn(Optional.of(user));

        when(userService.isAccountLocked(user)).thenReturn(false);

        when(authenticationManager.authenticate(any(
                UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);

        when(jwtService.issueAccessToken(
                3L,
                "doctor1",
                "DOCTOR")).thenReturn("access-token");

        when(jwtService.issueRefreshToken("doctor1")).thenReturn("refresh-token");

        when(jwtService.getAccessTokenExpiry()).thenReturn(900L);

        authService.login(request);

        verify(userService).recordSuccessfulLogin(user);

        verify(userService, never()).recordFailedAttempt(user);
    }

    @Test
    void refreshShouldReturnNewAccessTokenForValidRefreshToken() {

        RefreshRequest request =
                new RefreshRequest("valid-refresh-token");

        when(jwtService.extractType("valid-refresh-token")).thenReturn("refresh");

        when(jwtService.extractUsername("valid-refresh-token")).thenReturn("doctor1");

        when(userDetailsService.loadUserByUsername("doctor1")).thenReturn(userDetails());

        when(userRepository.findByUsername("doctor1")).thenReturn(Optional.of(user));

        when(userService.isAccountLocked(user)).thenReturn(false);

        when(jwtService.issueAccessToken(
                3L,
                "doctor1",
                "DOCTOR")).thenReturn("new-access-token");

        when(jwtService.getAccessTokenExpiry()).thenReturn(900L);

        AuthResponse response = authService.refresh(request);

        assertNotNull(response);

        verify(jwtService).issueAccessToken(
                3L,
                "doctor1",
                "DOCTOR");
    }

    @Test
    void refreshShouldRejectInvalidTokenType() {

        RefreshRequest request = new RefreshRequest("invalid-token");

        when(jwtService.extractType("invalid-token")).thenReturn("access");

        assertThrows(
                BadCredentialsException.class,
                () -> authService.refresh(request)
        );

        verify(jwtService, never()).extractUsername(anyString());

        verify(jwtService, never()).issueAccessToken(anyLong(), anyString(), anyString());
    }

    @Test
    void refreshShouldRejectInactiveAccount() {

        RefreshRequest request =  new RefreshRequest("valid-refresh-token");

        user.setIsActive(false);

        when(jwtService.extractType("valid-refresh-token")).thenReturn("refresh");

        when(jwtService.extractUsername("valid-refresh-token")) .thenReturn("doctor1");

        when(userDetailsService.loadUserByUsername("doctor1")).thenReturn(userDetails());

        when(userRepository.findByUsername("doctor1")).thenReturn(Optional.of(user));

        assertThrows(
                BadCredentialsException.class,
                () -> authService.refresh(request)
        );

        verify(jwtService, never()).issueAccessToken(anyLong(), anyString(), anyString());
    }

    @Test
    void refreshShouldRejectLockedAccount() {

        RefreshRequest request = new RefreshRequest("valid-refresh-token");

        when(jwtService.extractType("valid-refresh-token")) .thenReturn("refresh");

        when(jwtService.extractUsername("valid-refresh-token")).thenReturn("doctor1");

        when(userDetailsService.loadUserByUsername("doctor1")).thenReturn(userDetails());

        when(userRepository.findByUsername("doctor1")).thenReturn(Optional.of(user));

        when(userService.isAccountLocked(user)).thenReturn(true);

        assertThrows(
                AccountLockedException.class,
                () -> authService.refresh(request)
        );

        verify(jwtService, never()).issueAccessToken(anyLong(), anyString(), anyString());
    }

    @Test
    void requestPasswordResetShouldDelegateToPasswordResetService() {

        var request = new com.onecare.backend.dto.request.ForgotPasswordRequest("doctor1@example.com");

        authService.requestPasswordReset(request);

        verify(passwordResetService).requestPasswordReset("doctor1@example.com");
        verifyNoMoreInteractions(passwordResetService);
    }

    @Test
    void resetPasswordShouldDelegateToPasswordResetService() {

        var request = new com.onecare.backend.dto.request.ResetPasswordRequest("raw-token", "NewPassword123!");

        authService.resetPassword(request);

        verify(passwordResetService).resetPassword("raw-token", "NewPassword123!");
        verifyNoMoreInteractions(passwordResetService);
    }

    private AppUserDetailsService.AppUserDetails userDetails() {

        return new AppUserDetailsService.AppUserDetails(
                user.getUserId(),
                user.getUsername(),
                user.getPasswordHash(),
                true,
                true,
                true,
                true,
                java.util.Set.of()
        );
    }
}
