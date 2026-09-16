package com.onecare.backend.controller;

import com.onecare.backend.dto.ApiResponse;
import com.onecare.backend.dto.response.AuthResponse;
import com.onecare.backend.dto.request.LoginRequest;
import com.onecare.backend.dto.request.RefreshRequest;
import com.onecare.backend.dto.response.UserResponse;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.Role;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.security.AppUserDetailsService;
import com.onecare.backend.security.JwtService;
import com.onecare.backend.security.SecurityUtil;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
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

        Role role = user.getRole();

        String accessToken = jwtService.issueAccessToken(
                user.getUserId(), request.username(), role.name());

        String refreshToken = jwtService.issueRefreshToken(request.username());

        return ResponseEntity.ok(
                AuthResponse.of(accessToken, refreshToken, jwtService.getAccessTokenExpiry(), role));
    }

//    @PostMapping("/refresh")
//    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
//        if (!"refresh".equals(jwtService.extractType(request.refreshToken()))) {
//            return ResponseEntity.status(401).build();
//        }
//
//        String username = jwtService.extractUsername(request.refreshToken());
//        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
//        User user = userRepository.findByUsername(userDetails.getUsername())
//                .orElseThrow(() -> new IllegalArgumentException("User not found for refresh"));
//        String role = user.getRole().name();
//
//        String newAccessToken = jwtService.issueAccessToken(
//                user.getUserId(),
//                username,
//                role.name()
//        );
//
//        return ResponseEntity.ok(
//                AuthResponse.of(
//                        newAccessToken,
//                        request.refreshToken(),
//                        jwtService.getAccessTokenExpiry(),
//                        role
//                )
//        );
//    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> me() {

        String username = SecurityUtil.getCurrentUsername()
                .orElseThrow(() -> new IllegalArgumentException("Authenticated user not found"));

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        UserResponse userResponse = UserResponse.from(user);

        return ResponseEntity.ok(
                new ApiResponse<>(true, "Profile retrieved successfully", userResponse )
        );
    }
}