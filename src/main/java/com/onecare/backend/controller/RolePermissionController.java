package com.onecare.backend.controller;

import com.onecare.backend.dto.ApiResponse;
import com.onecare.backend.dto.request.RolePermissionsRequest;
import com.onecare.backend.dto.response.RolePermissionsResponse;
import com.onecare.backend.security.Permission;
import com.onecare.backend.service.RolePermissionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Role Permissions", description = "Clinic configuration: configurable role permissions (FR003, UC3)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/roles/{role}/permissions")
public class RolePermissionController {

    private final RolePermissionService rolePermissionService;

    public RolePermissionController(RolePermissionService rolePermissionService) {
        this.rolePermissionService = rolePermissionService;
    }

    @Operation(summary = "Get a role's permissions",
            description = "Returns the role's current permission set (UC3 step 2). Requires ROLE_PERMISSION_READ.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Permissions retrieved"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing ROLE_PERMISSION_READ permission"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Unknown role")
    })
    @GetMapping
    @PreAuthorize("hasAuthority('" + Permission.ROLE_PERMISSION_READ + "')")
    public ResponseEntity<ApiResponse<RolePermissionsResponse>> getPermissions(@PathVariable String role) {
        RolePermissionsResponse response = rolePermissionService.getPermissions(role);
        return ResponseEntity.ok(new ApiResponse<>(true, "Permissions retrieved successfully", response));
    }

    @Operation(summary = "Replace a role's permissions",
            description = "Replaces the role's full permission set. Enforces the hard business rules: "
                    + "BR004 (privileged permissions are SUPER_ADMIN-only), "
                    + "BR005 (the SUPER_ADMIN set cannot be modified), "
                    + "BR006 (no empty sets, catalog permissions only). "
                    + "Violations return 422 naming the rule and persist nothing. Requires ROLE_PERMISSION_UPDATE.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Permissions replaced"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Unknown permission value(s)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing ROLE_PERMISSION_UPDATE permission"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Unknown role"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "BR004-BR006 violation; nothing persisted")
    })
    @PutMapping
    @PreAuthorize("hasAuthority('" + Permission.ROLE_PERMISSION_UPDATE + "')")
    public ResponseEntity<ApiResponse<RolePermissionsResponse>> updatePermissions(
            @PathVariable String role,
            @RequestBody RolePermissionsRequest request) {
        RolePermissionsResponse response = rolePermissionService.updatePermissions(role, request);
        return ResponseEntity.ok(new ApiResponse<>(true, "Permissions updated successfully", response));
    }
}
