package com.onecare.backend.service;

import com.onecare.backend.dto.request.LoginRequest;
import com.onecare.backend.dto.request.RefreshRequest;
import com.onecare.backend.dto.response.AuthResponse;
import com.onecare.backend.dto.response.UserResponse;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.Role;
import com.onecare.backend.exception.AccountLockedException;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.security.AppUserDetailsService;
import com.onecare.backend.security.JwtService;
import com.onecare.backend.security.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

@Service
public class AuthServiceImpl implements AuthService {

        private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

        private final AuthenticationManager authenticationManager;
        private final AppUserDetailsService userDetailsService;
        private final UserRepository userRepository;
        private final JwtService jwtService;
        private final UserService userService;

        public AuthServiceImpl(
                        AuthenticationManager authenticationManager,
                        AppUserDetailsService userDetailsService,
                        UserRepository userRepository,
                        JwtService jwtService,
                        UserService userService) {

                this.authenticationManager = authenticationManager;
                this.userDetailsService = userDetailsService;
                this.userRepository = userRepository;
                this.jwtService = jwtService;
                this.userService = userService;
        }

        @Override
        public AuthResponse login(LoginRequest request) {

                User user = userRepository.findByUsername(request.username())
                        .orElseThrow(() -> {
                                log.warn(
                                        "Login failed | username={} | reason=USER_NOT_FOUND",
                                        request.username()
                                );

                                return new BadCredentialsException("Invalid username or password");
                        });

                if (userService.isAccountLocked(user)) {

                        log.warn(
                                "Login blocked | username={} | role={} | reason=ACCOUNT_LOCKED",
                                user.getUsername(),
                                user.getRole());

                                throw new AccountLockedException("Account is temporarily locked until " + user.getLockedUntil());
                }

                try {

                        authenticationManager.authenticate(
                                        new UsernamePasswordAuthenticationToken(
                                                        request.username(),
                                                        request.password()));

                        userService.recordSuccessfulLogin(user);

                        Role role = user.getRole();

                        String accessToken = jwtService.issueAccessToken(
                                        user.getUserId(),
                                        user.getUsername(),
                                        role.name());

                        String refreshToken = jwtService.issueRefreshToken(
                                        user.getUsername());

                        log.info(
                                "Login successful | username={} | role={}",
                                user.getUsername(),
                                role
                        );

                        return AuthResponse.of(
                                        accessToken,
                                        refreshToken,
                                        jwtService.getAccessTokenExpiry(),
                                        role);

                } catch (BadCredentialsException ex) {
                        boolean accountLocked = userService.recordFailedAttempt(user);

                        if (accountLocked) {

                                log.warn(
                                        "Login failed and account locked | username={} | role={}",
                                        user.getUsername(),
                                        user.getRole()
                                );

                                throw new AccountLockedException( "Account is temporarily locked until " + user.getLockedUntil() );
                        }

                        throw ex;
                }
        }

        @Override
        public AuthResponse refresh(RefreshRequest request) {

                String refreshToken = request.refreshToken();

                if (!"refresh".equals(jwtService.extractType(refreshToken))) {

                        log.warn(
                                "Refresh token rejected | reason=INVALID_TOKEN_TYPE"
                        );

                        throw new BadCredentialsException("Invalid refresh token");
                }

                String username = jwtService.extractUsername(refreshToken);

                UserDetails userDetails = userDetailsService.loadUserByUsername(username);

                User user = userRepository.findByUsername(userDetails.getUsername())
                                .orElseThrow(() -> new IllegalArgumentException("User not found for refresh"));

                if (!Boolean.TRUE.equals(user.getIsActive())) {

                        log.warn(
                                "Refresh token rejected | username={} | reason=ACCOUNT_INACTIVE",
                                username
                        );

                        throw new BadCredentialsException("Account is inactive");
                }

                if (userService.isAccountLocked(user)) {

                        log.warn(
                                "Refresh token rejected | username={} | reason=ACCOUNT_LOCKED",
                                username
                        );

                        throw new AccountLockedException( "Account is temporarily locked until " + user.getLockedUntil());
                }

                Role role = user.getRole();

                String newAccessToken = jwtService.issueAccessToken(
                                user.getUserId(),
                                username,
                                role.name());

                log.info(
                        "Access token refreshed | username={} | role={}",
                        username,
                        role
                );

                return AuthResponse.of(
                                newAccessToken,
                                refreshToken,
                                jwtService.getAccessTokenExpiry(),
                                role);
        }

        @Override
        public UserResponse getCurrentUser() {

                String username = SecurityUtil.getCurrentUsername()
                                .orElseThrow(() -> new IllegalArgumentException("Authenticated user not found"));

                User user = userRepository.findByUsername(username)
                                .orElseThrow(() -> new IllegalArgumentException("User not found"));

                return UserResponse.from(user);
        }
}
