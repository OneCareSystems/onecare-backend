package com.onecare.backend.service;

import com.onecare.backend.dto.request.AssignRoleRequest;
import com.onecare.backend.dto.request.UserCreateRequest;
import com.onecare.backend.dto.request.UserUpdateRequest;
import com.onecare.backend.dto.response.UserResponse;
import com.onecare.backend.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface UserService {

    // --- Existing (PR #26 / DDP-15) — unchanged ---
    boolean recordFailedAttempt(User user);

    void recordSuccessfulLogin(User user);

    UserResponse unlockAccount(Long id);

    boolean isAccountLocked(User user);

    // --- New (DDP-18) ---
    UserResponse createUser(UserCreateRequest request);

    Page<UserResponse> findAllUsers(Pageable pageable);

    UserResponse findUserById(Long id);

    UserResponse updateUser(Long id, UserUpdateRequest request);

    UserResponse assignRoleToUser(Long id, AssignRoleRequest request);

    void deactivateUser(Long id);
}