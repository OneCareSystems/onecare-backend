package com.onecare.backend.service;

import com.onecare.backend.dto.request.RolePermissionsRequest;
import com.onecare.backend.dto.response.RolePermissionsResponse;
import com.onecare.backend.entity.AuditLog;
import com.onecare.backend.entity.RolePermissionEntity;
import com.onecare.backend.enums.Role;
import com.onecare.backend.exception.BusinessRuleException;
import com.onecare.backend.exception.PermissionRuleViolationException;
import com.onecare.backend.exception.ResourceNotFoundException;
import com.onecare.backend.repository.AuditLogRepository;
import com.onecare.backend.repository.RolePermissionEntityRepository;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.security.PermissionCatalog;
import com.onecare.backend.security.RolePermission;
import com.onecare.backend.security.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Configurable role permissions (FR003, UC3) with the BR004-BR006 hard rules
 * enforced at the endpoint: violations return 422 naming the rule and nothing
 * is persisted.
 */
@Service
public class RolePermissionServiceImpl implements RolePermissionService {

    private static final Logger log = LoggerFactory.getLogger(RolePermissionServiceImpl.class);

    private final RolePermissionEntityRepository rolePermissionEntityRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;

    public RolePermissionServiceImpl(RolePermissionEntityRepository rolePermissionEntityRepository,
            UserRepository userRepository,
            AuditLogRepository auditLogRepository) {
        this.rolePermissionEntityRepository = rolePermissionEntityRepository;
        this.userRepository = userRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public RolePermissionsResponse getPermissions(String roleName) {
        Role role = resolveRole(roleName);
        return new RolePermissionsResponse(role.name(), currentPermissions(role));
    }

    @Override
    @Transactional
    public RolePermissionsResponse updatePermissions(String roleName, RolePermissionsRequest request) {
        Role role = resolveRole(roleName);

        // BR005: the Super Admin's own permission set can never be changed,
        // which also prevents removing your own access.
        if (role == Role.SUPER_ADMIN) {
            throw new PermissionRuleViolationException(
                    "BR005: the SUPER_ADMIN role's own permission set cannot be modified");
        }

        // BR006: a role must always keep at least one permission, and only
        // catalog-known permissions may be persisted.
        Set<String> requested = request.permissions() == null
                ? Set.of()
                : new LinkedHashSet<>(request.permissions());
        if (requested.isEmpty()) {
            throw new PermissionRuleViolationException(
                    "BR006: a role's permission set cannot be empty");
        }
        List<String> unknown = requested.stream()
                .filter(permission -> !PermissionCatalog.ALL.contains(permission))
                .sorted()
                .toList();
        if (!unknown.isEmpty()) {
            throw new BusinessRuleException("Unknown permission(s): " + String.join(", ", unknown));
        }

        // BR004: privileged permissions belong to SUPER_ADMIN only.
        String privileged = requested.stream()
                .filter(PermissionCatalog.PRIVILEGED::contains)
                .findFirst()
                .orElse(null);
        if (privileged != null) {
            throw new PermissionRuleViolationException(
                    "BR004: " + privileged + " is a privileged permission that only the SUPER_ADMIN role may hold");
        }

        // Replace semantics: queued removes must hit the database before the
        // new rows are inserted (IDENTITY saves flush immediately), otherwise
        // surviving old rows break the unique (role, permission) constraint.
        rolePermissionEntityRepository.deleteAll(rolePermissionEntityRepository.findByRole(role));
        rolePermissionEntityRepository.flush();
        requested.forEach(permission ->
                rolePermissionEntityRepository.save(new RolePermissionEntity(role, permission)));

        writeAudit(role, requested);
        log.info("Replaced permissions of role {} with {} permission(s)", role, requested.size());
        return new RolePermissionsResponse(role.name(), List.copyOf(requested));
    }

    private List<String> currentPermissions(Role role) {
        List<String> persisted = rolePermissionEntityRepository.findByRole(role).stream()
                .map(RolePermissionEntity::getPermission)
                .sorted()
                .toList();
        if (!persisted.isEmpty()) {
            return persisted;
        }
        // No persisted rows (e.g. before seeding): static defaults
        return RolePermission.getPermissions(role).stream().sorted().toList();
    }

    private Role resolveRole(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            throw new ResourceNotFoundException("Role not found: " + roleName);
        }
        try {
            return Role.valueOf(roleName.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ResourceNotFoundException("Role not found: " + roleName);
        }
    }

    private void writeAudit(Role role, Set<String> permissions) {
        SecurityUtil.getCurrentUserId()
                .flatMap(userRepository::findById)
                .ifPresent(actor -> {
                    AuditLog audit = new AuditLog();
                    audit.setUser(actor);
                    audit.setAction("PERMISSIONS_UPDATE");
                    audit.setActionType("UPDATE");
                    audit.setTimestamp(java.time.LocalDateTime.now());
                    audit.setEntityType("Role");
                    audit.setEntityId(role.name());
                    audit.setDescription("Replaced permissions of role " + role.name()
                            + " (" + permissions.size() + " permission(s))");
                    auditLogRepository.save(audit);
                });
    }
}
