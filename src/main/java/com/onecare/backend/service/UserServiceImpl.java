package com.onecare.backend.service;

import com.onecare.backend.dto.request.AssignRoleRequest;
import com.onecare.backend.dto.request.UserCreateRequest;
import com.onecare.backend.dto.request.UserUpdateRequest;
import com.onecare.backend.dto.response.UserResponse;
import com.onecare.backend.entity.User;
import com.onecare.backend.exception.DuplicateUserException;
import com.onecare.backend.exception.ResourceNotFoundException;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.security.Permission;
import com.onecare.backend.security.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@Transactional
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

    private static final int MAX_FAILED_ATTEMPTS = 5;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public boolean recordFailedAttempt(User user) {

        userRepository.recordFailedAttempt(user.getUserId());

        User updatedUser = findUserByIdInternal(user.getUserId());

        int failedAttempts = updatedUser.getFailedAttempts();

        log.warn(
                "Authentication failed | username={} | role={} | failedAttempts={}",
                updatedUser.getUsername(),
                updatedUser.getRole(),
                failedAttempts
        );

        boolean accountLocked = failedAttempts >= MAX_FAILED_ATTEMPTS;

        if (accountLocked) {
            updatedUser.setLockedUntil(LocalDateTime.now().plusMinutes(15));

            log.warn(
                    "Account locked | username={} | role={} | failedAttempts={} | lockedUntil={}",
                    updatedUser.getUsername(),
                    updatedUser.getRole(),
                    failedAttempts,
                    updatedUser.getLockedUntil()
            );
        }

        return accountLocked;
    }

    /**
     * Resets the failed-login state after successful authentication.
     */
    @Override
    public void recordSuccessfulLogin(User user) {

        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        user.setLastLogin(LocalDateTime.now());

        log.info(
                "Successful login | username={} | role={}",
                user.getUsername(),
                user.getRole()
        );
    }

    @Override
    @PreAuthorize("hasAuthority('" + Permission.USER_ROLE_ASSIGN + "')")
    public UserResponse unlockAccount(Long id) {

        User user = findUserByIdInternal(id);

        user.setFailedAttempts(0);
        user.setLockedUntil(null);

        String performedBy = SecurityUtil.getCurrentUsername()
                .orElse("SYSTEM");

        log.info(
                "Account unlocked | targetUser={} | targetRole={} | performedBy={}",
                user.getUsername(),
                user.getRole(),
                performedBy
        );

        return toResponse(user);
    }


    @Override
    public boolean isAccountLocked(User user) {

        LocalDateTime lockedUntil = user.getLockedUntil();

        // No active lock
        if (lockedUntil == null) {
            return false;
        }

        // Lock has expired
        if (!lockedUntil.isAfter(LocalDateTime.now())) {

            user.setFailedAttempts(0);
            user.setLockedUntil(null);

            log.info(
                    "Account lock expired | username={} | role={}",
                    user.getUsername(),
                    user.getRole()
            );

            return false;
        }

        return true;
    }

    // =====================================================================
    // NEW — DDP-18 User & Access Management APIs
    // =====================================================================

    @Override
    public UserResponse createUser(UserCreateRequest request) {

        if (userRepository.existsByUsername(request.username())
                || userRepository.existsByEmail(request.email())) {
            throw new DuplicateUserException("Username or email already exists");
        }

        User user = new User();
        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(request.role());
        user.setIsActive(true);
        // failedAttempts / lockedUntil left untouched — entity field defaults apply

        User saved = userRepository.save(user);

        String performedBy = SecurityUtil.getCurrentUsername().orElse("SYSTEM");
        log.info("User {} created by {}", saved.getUsername(), performedBy);
        // TODO(DDP-26): replace with persisted audit event

        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserResponse> findAllUsers(Pageable pageable) {
        return userRepository.findAll(pageable).map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse findUserById(Long id) {
        return toResponse(findUserByIdInternal(id));
    }

    @Override
    public UserResponse updateUser(Long id, UserUpdateRequest request) {

        User user = findUserByIdInternal(id);

        if (request.email() != null
                && !request.email().equals(user.getEmail())
                && userRepository.existsByEmail(request.email())) {
            throw new DuplicateUserException("Email already exists");
        }

        if (request.email() != null) {
            user.setEmail(request.email());
        }

        User saved = userRepository.save(user);

        String performedBy = SecurityUtil.getCurrentUsername().orElse("SYSTEM");
        log.info("User {} updated by {}", saved.getUsername(), performedBy);
        // TODO(DDP-26): replace with persisted audit event

        return toResponse(saved);
    }

    @Override
    @PreAuthorize("hasAuthority('" + Permission.USER_ROLE_ASSIGN + "')")
    public UserResponse assignRoleToUser(Long id, AssignRoleRequest request) {

        User user = findUserByIdInternal(id);
        user.setRole(request.role());

        User saved = userRepository.save(user);

        String performedBy = SecurityUtil.getCurrentUsername().orElse("SYSTEM");
        log.info(
                "Role changed | targetUser={} | newRole={} | performedBy={}",
                saved.getUsername(),
                saved.getRole(),
                performedBy
        );
        // TODO(DDP-26): replace with persisted audit event

        return toResponse(saved);
    }

    @Override
    public void deactivateUser(Long id) {

        User user = findUserByIdInternal(id);
        user.setIsActive(false); // soft delete — row is never removed

        userRepository.save(user);

        String performedBy = SecurityUtil.getCurrentUsername().orElse("SYSTEM");
        log.info("User {} deactivated by {}", user.getUsername(), performedBy);
        // TODO(DDP-26): replace with persisted audit event
    }

    private User findUserByIdInternal(Long id) {

        return userRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("User not found with id: " + id)
                );
    }

    private UserResponse toResponse(User user) {

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