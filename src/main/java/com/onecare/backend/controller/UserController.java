package com.onecare.backend.controller;

import com.onecare.backend.dto.ApiResponse;
import com.onecare.backend.dto.request.AssignRoleRequest;
import com.onecare.backend.dto.request.UserCreateRequest;
import com.onecare.backend.dto.request.UserUpdateRequest;
import com.onecare.backend.dto.response.UserResponse;
import com.onecare.backend.security.Permission;
import com.onecare.backend.service.UserService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;


@Tag(name = "User Management", description = "Super Admin user & access management")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    
    @Operation(summary = "Create a user", description = "Creates a user with a hashed password. Requires USER_CREATE.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "User created"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error or invalid role"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing USER_CREATE permission"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Username or email already exists")
    })
    
    @PostMapping
    @PreAuthorize("hasAuthority('" + Permission.USER_CREATE + "')")
    public ResponseEntity<ApiResponse<?>> createUser(@Valid @RequestBody UserCreateRequest request) {
        UserResponse response = userService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "User created successfully", response));
    }

   
    @Operation(summary = "List users (paginated)", description = "Supports page, size and sort query params. Requires USER_READ_ALL.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Users retrieved"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing USER_READ_ALL permission")
    })
   
    @GetMapping
    @PreAuthorize("hasAuthority('" + Permission.USER_READ_ALL + "')")
    public ResponseEntity<ApiResponse<Page<?>>> findAllUsers(Pageable pageable) {
        Page<UserResponse> page = userService.findAllUsers(pageable);
        return ResponseEntity.ok(new ApiResponse<>(true, "Users retrieved successfully", page));
    }

    
    @Operation(summary = "Get a user by id", description = "Requires USER_READ_ALL or USER_READ_OWN.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "User retrieved"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing read permission"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found")
    })
  
    @GetMapping("/{id}")
    @PreAuthorize(
            "hasAuthority('" + Permission.USER_READ_ALL + "')"
                    + " or hasAuthority('" + Permission.USER_READ_OWN + "')")
    public ResponseEntity<ApiResponse<?>> findUserById(@PathVariable Long id) {
        UserResponse response = userService.findUserById(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "User retrieved successfully", response));
    }

    
    @Operation(summary = "Update a user's email", description = "Only the email can be changed here. Role and unlock have their own endpoints.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "User updated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing USER_UPDATE permission"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Email already exists")
    })
  
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permission.USER_UPDATE + "')")
    public ResponseEntity<ApiResponse<?>> updateUser(@PathVariable Long id,
                                                      @Valid @RequestBody UserUpdateRequest request) {
        UserResponse response = userService.updateUser(id, request);
        return ResponseEntity.ok(new ApiResponse<>(true, "User updated successfully", response));
    }

    
    @Operation(summary = "Deactivate a user (soft delete)", description = "Sets isActive=false. The row is NOT removed from the database.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "User deactivated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing USER_DELETE permission"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found")
    })
    
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permission.USER_DELETE + "')")
    public ResponseEntity<ApiResponse<?>> deleteUser(@PathVariable Long id) {
        userService.deactivateUser(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "User deactivated successfully"));
    }

    
    @Operation(summary = "Assign a role to a user", description = "Role must be a valid Role enum value.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Role assigned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid role"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing USER_ROLE_ASSIGN permission"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found")
    })
   
    @PutMapping("/{id}/role")
    @PreAuthorize("hasAuthority('" + Permission.USER_ROLE_ASSIGN + "')")
    public ResponseEntity<ApiResponse<?>> assignRoleToUser(@PathVariable Long id,
                                                            @Valid @RequestBody AssignRoleRequest request) {
        UserResponse response = userService.assignRoleToUser(id, request);
        return ResponseEntity.ok(new ApiResponse<>(true, "Role assigned successfully", response));
    }

    
    @Operation(summary = "Unlock a locked account", description = "Resets failed attempts and clears the lock.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Account unlocked"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing USER_ROLE_ASSIGN permission"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found")
    })
   
    @PutMapping("/{id}/unlock")
    @PreAuthorize("hasAuthority('" + Permission.USER_ROLE_ASSIGN + "')")
    public ResponseEntity<ApiResponse<UserResponse>> unlockAccount(@PathVariable Long id) {
        UserResponse response = userService.unlockAccount(id);
        return ResponseEntity.ok(
                new ApiResponse<>(
                        true,
                        "Account unlocked successfully",
                        response));
    }

    
    @Operation(summary = "Reserved: admin-triggered password reset (DDP-16)",
               description = "Placeholder route. Always returns 501 until DDP-16 is merged.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "501", description = "Not implemented until DDP-16 lands"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing USER_UPDATE permission")
    })
  
    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('" + Permission.USER_UPDATE + "')")
    public ResponseEntity<Void> resetPassword(@PathVariable Long id) {
        // AC3: reserved until DDP-16 admin-triggered reset flow lands — must be 501, never 404
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}