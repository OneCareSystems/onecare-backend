package com.onecare.backend.service;

import com.onecare.backend.dto.request.RolePermissionsRequest;
import com.onecare.backend.dto.response.RolePermissionsResponse;

public interface RolePermissionService {

    /** The role's current permissions (UC3 step 2). */
    RolePermissionsResponse getPermissions(String roleName);

    /** Validates BR004-BR006, then replaces the role's permission set. */
    RolePermissionsResponse updatePermissions(String roleName, RolePermissionsRequest request);
}
