package com.onecare.backend.entity;

import com.onecare.backend.enums.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One persisted (role, permission) pair (FR003, UC3). The set of rows for a
 * role is that role's configurable permission set; defaults are seeded from
 * {@link com.onecare.backend.security.RolePermission} on startup.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "role_permissions", uniqueConstraints = {
        @UniqueConstraint(name = "uq_role_permission", columnNames = {"role", "permission"})
})
public class RolePermissionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Column(name = "permission", nullable = false, length = 100)
    private String permission;

    public RolePermissionEntity(Role role, String permission) {
        this.role = role;
        this.permission = permission;
    }
}
