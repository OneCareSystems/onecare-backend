package com.onecare.backend.service;

import com.onecare.backend.dto.response.UserResponse;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.Role;
import com.onecare.backend.exception.ResourceNotFoundException;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.security.Permission;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@Transactional
public class UserServiceImpl implements UserService {

    private static final int MAX_FAILED_ATTEMPTS = 10;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder) {

        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }


    @Override
    public boolean recordFailedAttempt(User user) {

        int currentAttempts = user.getFailedAttempts() == null
                ? 0 : user.getFailedAttempts();

        int newAttempts = currentAttempts + 1;

        user.setFailedAttempts(newAttempts);

        /*
         * SUPER_ADMIN is not automatically locked.
         *
         * Otherwise, if the only SUPER_ADMIN account becomes locked,
         * there would be no administrator available to unlock it.
         *
         * Failed attempts are still recorded for auditing/security monitoring.
         */
        if (user.getRole() != Role.SUPER_ADMIN
                && newAttempts >= MAX_FAILED_ATTEMPTS) {

            user.setAccountLocked(true);

            // Lock is permanent until an authorized admin unlocks it.
            user.setLockedUntil(null);

            return true;
        }
        return false;
    }

    /**
     * Resets login failure state after successful authentication.
     */
    @Override
    public void recordSuccessfulLogin(User user) {
        user.setFailedAttempts(0);
        user.setAccountLocked(false);
        user.setLockedUntil(null);
        user.setLastLogin(LocalDateTime.now());
    }

    @Override
    @PreAuthorize("hasAuthority('" + Permission.USER_ROLE_ASSIGN + "')")
    public UserResponse unlockAccount(Long id) {

        User user = findUserById(id);

        user.setAccountLocked(false);
        user.setFailedAttempts(0);
        user.setLockedUntil(null);

        return toResponse(user);
    }

    @Override
    public boolean isAccountLocked(User user) {

        return Boolean.TRUE.equals(user.getAccountLocked());
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
                user.getAccountLocked(),
                user.getFailedAttempts(),
                user.getLockedUntil(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
