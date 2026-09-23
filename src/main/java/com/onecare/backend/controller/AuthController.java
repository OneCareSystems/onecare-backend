package com.onecare.backend.controller;

import com.onecare.backend.dto.ApiResponse;
import com.onecare.backend.dto.request.ForgotPasswordRequest;
import com.onecare.backend.dto.request.LoginRequest;
import com.onecare.backend.dto.request.RefreshRequest;
import com.onecare.backend.dto.request.ResetPasswordRequest;
import com.onecare.backend.dto.response.AuthResponse;
import com.onecare.backend.dto.response.UserResponse;
import com.onecare.backend.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Authentication, token refresh, profile, and password reset")
public class AuthController {

        private final AuthService authService;

        public AuthController(AuthService authService) {
                this.authService = authService;
        }

        @Operation(summary = "Login", description = "Authenticates with username and password "
                        + "and returns access and refresh tokens.")
        @ApiResponses({
                        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Login successful"),
                        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Invalid credentials"),
                        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "423", description = "Account temporarily locked"),
                        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Too many requests")
        })
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

        @Operation(summary = "Refresh tokens", description = "Exchanges a valid refresh token "
                        + "for a new access/refresh token pair.")
        @ApiResponses({
                        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Token refreshed successfully"),
                        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Invalid, expired, or wrong-type token"),
                        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Too many requests")
        })
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

        @Operation(summary = "Current profile", description = "Returns the authenticated user's profile.")
        @ApiResponses({
                        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Profile retrieved successfully"),
                        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing or invalid access token")
        })
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

        @Operation(summary = "Request password reset", description = "Starts the password reset flow. "
                        + "Always returns the same success response whether or not the email is registered "
                        + "(prevents account enumeration). If registered, a reset link containing a "
                        + "single-use token (default TTL 30 minutes) is emailed. Rate limited per IP.")
        @ApiResponses({
                        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Generic success (email sent if registered)"),
                        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed (invalid email format)"),
                        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Too many requests"),
                        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Email delivery unavailable")
        })
        @PostMapping("/forgot-password")
        public ResponseEntity<ApiResponse<Void>> forgotPassword(
                        @Valid @RequestBody ForgotPasswordRequest request) {

                authService.requestPasswordReset(request);

                return ResponseEntity.ok(
                                new ApiResponse<>(
                                                true,
                                                "If the email address is registered, a password reset link has been sent."));
        }

        @Operation(summary = "Reset password", description = "Sets a new password using the raw token "
                        + "from the reset email. On success the account is unlocked "
                        + "(failedAttempts/lock cleared) and the token is consumed (single-use). "
                        + "Unknown, expired, and already-used tokens are rejected with the same generic message.")
        @ApiResponses({
                        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Password has been reset successfully"),
                        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed or invalid/expired/used token"),
                        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Too many requests")
        })
        @PostMapping("/reset-password")
        public ResponseEntity<ApiResponse<Void>> resetPassword(
                        @Valid @RequestBody ResetPasswordRequest request) {

                authService.resetPassword(request);

                return ResponseEntity.ok(
                                new ApiResponse<>(
                                                true,
                                                "Password has been reset successfully"));
        }
}
