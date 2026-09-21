package com.onecare.backend.service;

import com.onecare.backend.dto.response.UserResponse;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.Role;
import com.onecare.backend.exception.ResourceNotFoundException;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.security.Permission;
import com.onecare.backend.security.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@Transactional
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

    private static final int MAX_FAILED_ATTEMPTS = 5;

    private final UserRepository userRepository;

    public UserServiceImpl( UserRepository userRepository) {
        this.userRepository = userRepository;
    }
    @Override
    public boolean recordFailedAttempt(User user) {

        userRepository.recordFailedAttempt(user.getUserId());

        User updatedUser = findUserById(user.getUserId());

        int failedAttempts = updatedUser.getFailedAttempts();

        log.warn(
                "Authentication failed | username={} | role={} | failedAttempts={}",
                updatedUser.getUsername(),
                updatedUser.getRole(),
                failedAttempts
        );

        boolean accountLocked = failedAttempts >= MAX_FAILED_ATTEMPTS;

        if (accountLocked) {
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

        User user = findUserById(id);

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

    private User findUserById(Long id) {

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
