package com.onecare.backend.controller;

import com.onecare.backend.dto.AuthResponse;
import com.onecare.backend.dto.LoginRequest;
import com.onecare.backend.dto.RefreshRequest;
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
    private final JwtService jwtService;

    public AuthController(AuthenticationManager authenticationManager,
            AppUserDetailsService userDetailsService,
            JwtService jwtService) {
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));

        UserDetails userDetails = userDetailsService.loadUserByUsername(request.username());
        String role = userDetails.getAuthorities().iterator().next().getAuthority();

        String accessToken = jwtService.issueAccessToken(request.username(), role);
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
        String role = userDetails.getAuthorities().iterator().next().getAuthority();

        String newAccessToken = jwtService.issueAccessToken(username, role);
        return ResponseEntity
                .ok(AuthResponse.of(newAccessToken, request.refreshToken(), jwtService.getAccessTokenExpiry()));
    }
}