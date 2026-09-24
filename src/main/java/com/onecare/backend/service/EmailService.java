package com.onecare.backend.service;

public interface EmailService {

    void sendPasswordResetEmail(String toEmail, String rawToken);
}
