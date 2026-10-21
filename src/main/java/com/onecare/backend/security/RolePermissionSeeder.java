package com.onecare.backend.security;

import com.onecare.backend.entity.RolePermissionEntity;
import com.onecare.backend.enums.Role;
import com.onecare.backend.repository.RolePermissionEntityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds the configurable permission sets (FR003) from the static defaults the
 * first time the application runs. A role that already has rows is left
 * untouched, so Super Admin customizations survive restarts.
 */
@Component
public class RolePermissionSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RolePermissionSeeder.class);

    private final RolePermissionEntityRepository rolePermissionEntityRepository;

    public RolePermissionSeeder(RolePermissionEntityRepository rolePermissionEntityRepository) {
        this.rolePermissionEntityRepository = rolePermissionEntityRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Role role : Role.values()) {
            if (!rolePermissionEntityRepository.findByRole(role).isEmpty()) {
                continue;
            }
            RolePermission.getPermissions(role).forEach(permission ->
                    rolePermissionEntityRepository.save(new RolePermissionEntity(role, permission)));
            log.info("Seeded default permissions for role {}", role);
        }
    }
}
