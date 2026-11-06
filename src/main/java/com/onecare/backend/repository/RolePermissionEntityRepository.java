package com.onecare.backend.repository;

import com.onecare.backend.entity.RolePermissionEntity;
import com.onecare.backend.enums.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RolePermissionEntityRepository extends JpaRepository<RolePermissionEntity, Long> {

    List<RolePermissionEntity> findByRole(Role role);

    void deleteByRole(Role role);
}
