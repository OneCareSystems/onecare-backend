package com.onecare.backend.config;

import com.onecare.backend.entity.User;
import com.onecare.backend.enums.Role;
import com.onecare.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("!test")
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private static final String DEFAULT_PASSWORD = "Password@123";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        String passwordHash = passwordEncoder.encode(DEFAULT_PASSWORD);

        createUser(
                "superadmin", "superadmin@onecare.com",
                Role.SUPER_ADMIN, passwordHash);

        createUser(
                "admin","admin@onecare.com",
                Role.ADMIN, passwordHash);

        createUser(
                "doctor", "doctor@onecare.com",
                Role.DOCTOR, passwordHash);

        createUser(
                "pharmacist", "pharmacist@onecare.com",
                Role.PHARMACIST, passwordHash);

        log.info("RBAC test users initialization completed");
    }

    private void createUser(
            String username, String email, Role role, String passwordHash ) {

        if (userRepository.findByUsername(username).isPresent()) {
            log.debug("Test user already exists: {}", username);
            return;
        }

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordHash);
        user.setRole(role);
        user.setIsActive(true);
        user.setFailedAttempts(0);

        userRepository.save(user);

        log.info("Created RBAC test user: {} ({})", username, role);
    }
}