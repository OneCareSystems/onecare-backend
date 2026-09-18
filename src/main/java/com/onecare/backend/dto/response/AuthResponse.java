package com.onecare.backend.dto.response;

import com.onecare.backend.enums.Role;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        long expiresIn,
        String tokenType,
        Role role,
        String redirectUrl
) {

    public static AuthResponse of(String accessToken, String refreshToken, long expiresIn, Role role) {
        String redirectUrl = switch (role) {
            case SUPER_ADMIN, ADMIN -> "/admin/dashboard";
            case DOCTOR -> "/doctor/dashboard";
            case PHARMACIST -> "/pharmacist/dashboard";
        };
        return new AuthResponse(accessToken, refreshToken, expiresIn, "Bearer", role, redirectUrl);
    }

    public static AuthResponse of(String accessToken, String refreshToken, long expiresIn) {
        return new AuthResponse(accessToken, refreshToken, expiresIn, "Bearer", null, null);
    }
}