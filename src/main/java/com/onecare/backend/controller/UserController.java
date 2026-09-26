package com.onecare.backend.controller;

import com.onecare.backend.dto.ApiResponse;
import com.onecare.backend.dto.request.AssignRoleRequest;
import com.onecare.backend.dto.request.UserCreateRequest;
import com.onecare.backend.dto.request.UserUpdateRequest;
import com.onecare.backend.dto.response.UserResponse;
import com.onecare.backend.security.Permission;
import com.onecare.backend.service.UserService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permission.USER_CREATE + "')")
    public ResponseEntity<ApiResponse<?>> createUser(@Valid @RequestBody UserCreateRequest request) {
        UserResponse response = userService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "User created successfully", response));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permission.USER_READ_ALL + "')")
    public ResponseEntity<ApiResponse<Page<?>>> findAllUsers(Pageable pageable) {
        Page<UserResponse> page = userService.findAllUsers(pageable);
        return ResponseEntity.ok(new ApiResponse<>(true, "Users retrieved successfully", page));
    }

    @GetMapping("/{id}")
    @PreAuthorize(
            "hasAuthority('" + Permission.USER_READ_ALL + "')"
                    + " or hasAuthority('" + Permission.USER_READ_OWN + "')")
    public ResponseEntity<ApiResponse<?>> findUserById(@PathVariable Long id) {
        UserResponse response = userService.findUserById(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "User retrieved successfully", response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permission.USER_UPDATE + "')")
    public ResponseEntity<ApiResponse<?>> updateUser(@PathVariable Long id,
                                                      @Valid @RequestBody UserUpdateRequest request) {
        UserResponse response = userService.updateUser(id, request);
        return ResponseEntity.ok(new ApiResponse<>(true, "User updated successfully", response));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permission.USER_DELETE + "')")
    public ResponseEntity<ApiResponse<?>> deleteUser(@PathVariable Long id) {
        userService.deactivateUser(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "User deactivated successfully"));
    }

    @PutMapping("/{id}/role")
    @PreAuthorize("hasAuthority('" + Permission.USER_ROLE_ASSIGN + "')")
    public ResponseEntity<ApiResponse<?>> assignRoleToUser(@PathVariable Long id,
                                                            @Valid @RequestBody AssignRoleRequest request) {
        UserResponse response = userService.assignRoleToUser(id, request);
        return ResponseEntity.ok(new ApiResponse<>(true, "Role assigned successfully", response));
    }

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

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('" + Permission.USER_UPDATE + "')")
    public ResponseEntity<Void> resetPassword(@PathVariable Long id) {
        // AC3: reserved until DDP-16 admin-triggered reset flow lands — must be 501, never 404
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}