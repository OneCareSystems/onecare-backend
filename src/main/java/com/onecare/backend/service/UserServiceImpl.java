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

        log.warn(
                "Authentication failed | username={} | role={} | failedAttempts={}",
                user.getUsername(),
                user.getRole(),
                newAttempts
        );

        /*
         * SUPER_ADMIN is excluded from automatic account locking.
         *
         * Failed attempts are still recorded so suspicious
         * authentication activity can be monitored.
         */
        if (user.getRole() != Role.SUPER_ADMIN
                && newAttempts >= MAX_FAILED_ATTEMPTS) {

            user.setAccountLocked(true);

            // Lock is permanent until an authorized admin unlocks it.
            user.setLockedUntil(null);

            log.warn(
                    "Account locked | username={} | role={} | failedAttempts={}",
                    user.getUsername(),
                    user.getRole(),
                    newAttempts
            );

            return true;
        }

        return false;
    }

    /**
     * Resets the failed-login state after successful authentication.
     */
    @Override
    public void recordSuccessfulLogin(User user) {

        user.setFailedAttempts(0);
        user.setAccountLocked(false);
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

        user.setAccountLocked(false);
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

        boolean locked = Boolean.TRUE.equals(user.getAccountLocked());

        if (locked) {

            log.warn(
                    "Login blocked | username={} | role={} | reason=ACCOUNT_LOCKED",
                    user.getUsername(),
                    user.getRole()
            );
        }

        return locked;
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
