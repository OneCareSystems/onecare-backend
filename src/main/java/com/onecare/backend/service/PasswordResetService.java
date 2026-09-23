package com.onecare.backend.service;

import com.onecare.backend.entity.User;

public interface PasswordResetService {

    void requestPasswordReset(String email);

    void resetPassword(String rawToken, String newPassword);
    
    String issueResetToken(User user);
}
