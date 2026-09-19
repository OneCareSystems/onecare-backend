package com.onecare.backend.controller;

import com.onecare.backend.dto.ApiResponse;
import com.onecare.backend.dto.request.LoginRequest;
import com.onecare.backend.dto.request.RefreshRequest;
import com.onecare.backend.dto.response.AuthResponse;
import com.onecare.backend.dto.response.UserResponse;
import com.onecare.backend.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

        private final AuthService authService;

        public AuthController(AuthService authService) {
                this.authService = authService;
        }

        @PostMapping("/login")
        public ResponseEntity<ApiResponse<AuthResponse>> login(
                        @Valid @RequestBody LoginRequest request) {

                AuthResponse response = authService.login(request);

                return ResponseEntity.ok(
                                new ApiResponse<>(
                                                true,
                                                "Login successful",
                                                response));
        }

        @PostMapping("/refresh")
        public ResponseEntity<ApiResponse<AuthResponse>> refresh(
                        @Valid @RequestBody RefreshRequest request) {

                AuthResponse response = authService.refresh(request);

                return ResponseEntity.ok(
                                new ApiResponse<>(
                                                true,
                                                "Token refreshed successfully",
                                                response));
        }

        @PreAuthorize("isAuthenticated()")
        @GetMapping("/me")
        public ResponseEntity<ApiResponse<UserResponse>> me() {

                UserResponse response = authService.getCurrentUser();

                return ResponseEntity.ok(
                                new ApiResponse<>(
                                                true,
                                                "Profile retrieved successfully",
                                                response));
        }
}
