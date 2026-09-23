package com.onecare.backend.service;

import com.onecare.backend.entity.User;

public interface PasswordResetService {

    /**
     * Invalidates any active reset tokens for the user, generates a new
     * high-entropy token, and persists only its SHA-256 hash.
     *
     * @return the raw token to place in the reset email — never persist or log it
     */
    String issueResetToken(User user);
}
