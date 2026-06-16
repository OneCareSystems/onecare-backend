package com.onecare.backend.controller;

import com.onecare.backend.dto.AuthResponse;
import com.onecare.backend.dto.LoginRequest;
import com.onecare.backend.dto.RefreshRequest;
import com.onecare.backend.entity.User;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.security.AppUserDetailsService;
import com.onecare.backend.security.JwtService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final AppUserDetailsService userDetailsService;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    public AuthController(AuthenticationManager authenticationManager,
            AppUserDetailsService userDetailsService,
            UserRepository userRepository,
            JwtService jwtService) {
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));

        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new IllegalArgumentException("User not found after authentication"));
        String role = user.getRole().name();

        String accessToken = jwtService.issueAccessToken(user.getUserId(), request.username(), role);
        String refreshToken = jwtService.issueRefreshToken(request.username());

        return ResponseEntity.ok(AuthResponse.of(accessToken, refreshToken, jwtService.getAccessTokenExpiry()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        if (!"refresh".equals(jwtService.extractType(request.refreshToken()))) {
            return ResponseEntity.status(401).build();
        }

        String username = jwtService.extractUsername(request.refreshToken());
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("User not found for refresh"));
        String role = user.getRole().name();

        String newAccessToken = jwtService.issueAccessToken(user.getUserId(), username, role);
        return ResponseEntity
                .ok(AuthResponse.of(newAccessToken, request.refreshToken(), jwtService.getAccessTokenExpiry()));
    }
}