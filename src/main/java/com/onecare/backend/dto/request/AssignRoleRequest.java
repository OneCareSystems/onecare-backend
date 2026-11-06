package com.onecare.backend.dto.request;

import com.onecare.backend.enums.Role;
import jakarta.validation.constraints.NotNull;

public record AssignRoleRequest(
        @NotNull Role role
) {
}