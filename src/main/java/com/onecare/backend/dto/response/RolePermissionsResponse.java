package com.onecare.backend.dto.response;

import java.util.List;

/** Current permission set of a role (UC3 step 2). */
public record RolePermissionsResponse(String role, List<String> permissions) {
}
