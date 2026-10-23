package com.onecare.backend.dto.request;

/**
 * Optional logout body. Both fields are optional so existing clients that
 * call POST /api/auth/logout with no body keep working unchanged — in that
 * case only the access token is blacklisted.
 *
 * <p>Clients that hold the refresh token SHOULD send it: otherwise the
 * 7-day refresh token stays valid after logout and can mint new access
 * tokens (SDS 2.5.1 single-session semantics).
 */
public record LogoutRequest(
        String refreshToken) {
}
