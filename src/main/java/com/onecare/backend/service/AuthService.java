package com.onecare.backend.service;

import com.onecare.backend.dto.request.ForgotPasswordRequest;
import com.onecare.backend.dto.request.LoginRequest;
import com.onecare.backend.dto.request.RefreshRequest;
import com.onecare.backend.dto.request.ResetPasswordRequest;
import com.onecare.backend.dto.response.AuthResponse;
import com.onecare.backend.dto.response.UserResponse;


public interface AuthService {

    public AuthResponse login(LoginRequest request);

    public AuthResponse refresh(RefreshRequest request);

    public UserResponse getCurrentUser();

    public void requestPasswordReset(ForgotPasswordRequest request);

    public void resetPassword(ResetPasswordRequest request);

    /**
     * Ends the session by blacklisting the access token, and the refresh
     * token when the client supplies one.
     *
     * @param accessToken  the Bearer access token from the Authorization header (required)
     * @param refreshToken the refresh token from the request body; may be null/blank,
     *                     in which case only the access token is blacklisted
     */
    public void logout(String accessToken, String refreshToken);
}
