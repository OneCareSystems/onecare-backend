package com.onecare.backend.controller;

import com.onecare.backend.dto.ApiResponse;
import com.onecare.backend.dto.response.UserResponse;
import com.onecare.backend.security.Permission;
import com.onecare.backend.service.UserService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permission.USER_CREATE + "')")
    public ResponseEntity<ApiResponse<?>> createUser() {
        return null;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permission.USER_READ_ALL + "')")
    public ResponseEntity<ApiResponse<Page<?>>> findAllUsers() {
        return null;
    }

    @GetMapping("/{id}")
    @PreAuthorize(
            "hasAuthority('" + Permission.USER_READ_ALL + "')"
                    + " or hasAuthority('" + Permission.USER_READ_OWN + "')")
    public ResponseEntity<ApiResponse<?>> findUserById( @PathVariable Long id) {
        return null;
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permission.USER_UPDATE + "')")
    public ResponseEntity<ApiResponse<?>> updateUser( @PathVariable Long id) {
        return null;
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permission.USER_DELETE + "')")
    public ResponseEntity<ApiResponse<?>> deleteUser( @PathVariable Long id) {
        return null;
    }

    @PutMapping("/{id}/role")
    @PreAuthorize("hasAuthority('" + Permission.USER_ROLE_ASSIGN + "')")
    public ResponseEntity<ApiResponse<?>> assignRoleToUser( @PathVariable Long id) {
        return null;
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
}