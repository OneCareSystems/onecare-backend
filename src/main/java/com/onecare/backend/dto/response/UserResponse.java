package com.onecare.backend.dto.response;

import com.onecare.backend.entity.User;
import com.onecare.backend.enums.Role;

import java.time.LocalDateTime;

public record UserResponse(
        Long userId, String username, String email,
        Role role, Boolean isActive, Integer failedAttempts,
        LocalDateTime lockedUntil, LocalDateTime createdAt, LocalDateTime updatedAt
) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getUserId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                user.getIsActive(),
                user.getFailedAttempts(),
                user.getLockedUntil(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}