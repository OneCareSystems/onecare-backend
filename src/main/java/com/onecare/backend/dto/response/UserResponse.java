package com.onecare.backend.dto.response;

import com.onecare.backend.entity.User;
import com.onecare.backend.enums.Role;

import java.time.LocalDateTime;

public record UserResponse(
        Long userId, String username, String email,
        Role role, Boolean isActive, LocalDateTime lastLogin ) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getUserId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                user.getIsActive(),
                user.getLastLogin()
        );
    }
}