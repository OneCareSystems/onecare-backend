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
}
