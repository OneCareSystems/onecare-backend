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

        boolean accountLocked = false;


        /*
         * Failed attempts are still recorded so suspicious
         * authentication activity can be monitored.
         */
        if (newAttempts >= MAX_FAILED_ATTEMPTS) {

            LocalDateTime lockedUntil = LocalDateTime.now().plusMinutes(15);

            user.setLockedUntil(lockedUntil);
            
            accountLocked = true;

            log.warn(
                    "Account locked | username={} | role={} | failedAttempts={} | lockedUntil={}",
                    user.getUsername(),
                    user.getRole(),
                    newAttempts,
                    lockedUntil
            );
        }

        userRepository.save(user);

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

        // Lock has expired
        if (lockedUntil != null && !lockedUntil.isAfter(LocalDateTime.now())) {

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
