package com.onecare.backend.service;

import com.onecare.backend.dto.request.RolePermissionsRequest;
import com.onecare.backend.dto.response.RolePermissionsResponse;
import com.onecare.backend.entity.RolePermissionEntity;
import com.onecare.backend.exception.BusinessRuleException;
import com.onecare.backend.exception.PermissionRuleViolationException;
import com.onecare.backend.exception.ResourceNotFoundException;
import com.onecare.backend.repository.AuditLogRepository;
import com.onecare.backend.repository.RolePermissionEntityRepository;
import com.onecare.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RolePermissionServiceImplTest {

    @Mock
    private RolePermissionEntityRepository rolePermissionEntityRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @InjectMocks
    private RolePermissionServiceImpl rolePermissionService;

    @Test
    void update_superAdminOwnSet_throwsBr005() {
        // AC5: removing the Super Admin's own access must be rejected with 422
        PermissionRuleViolationException exception = assertThrows(PermissionRuleViolationException.class,
                () -> rolePermissionService.updatePermissions("SUPER_ADMIN",
                        new RolePermissionsRequest(List.of("PATIENT_READ"))));

        assertTrue(exception.getMessage().contains("BR005"));
        verify(rolePermissionEntityRepository, never()).save(any());
        verify(rolePermissionEntityRepository, never()).deleteAll(any(java.lang.Iterable.class));
    }

    @Test
    void update_privilegedPermissionOnAdmin_throwsBr004() {
        PermissionRuleViolationException exception = assertThrows(PermissionRuleViolationException.class,
                () -> rolePermissionService.updatePermissions("ADMIN",
                        new RolePermissionsRequest(List.of("PATIENT_READ", "SYSTEM_BACKUP"))));

        assertTrue(exception.getMessage().contains("BR004"));
        verify(rolePermissionEntityRepository, never()).save(any());
        verify(rolePermissionEntityRepository, never()).deleteAll(any(java.lang.Iterable.class));
    }

    @Test
    void update_emptySet_throwsBr006() {
        PermissionRuleViolationException exception = assertThrows(PermissionRuleViolationException.class,
                () -> rolePermissionService.updatePermissions("ADMIN", new RolePermissionsRequest(List.of())));

        assertTrue(exception.getMessage().contains("BR006"));
        verify(rolePermissionEntityRepository, never()).save(any());
    }

    @Test
    void update_unknownPermission_throwsBadRequestAndPersistsNothing() {
        assertThrows(BusinessRuleException.class,
                () -> rolePermissionService.updatePermissions("ADMIN",
                        new RolePermissionsRequest(List.of("PATIENT_READ", "NOT_A_PERMISSION"))));

        verify(rolePermissionEntityRepository, never()).save(any());
        verify(rolePermissionEntityRepository, never()).deleteAll(any(java.lang.Iterable.class));
    }

    @Test
    void update_validSet_replacesRolePermissions() {
        when(rolePermissionEntityRepository.save(any(RolePermissionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RolePermissionsResponse response = rolePermissionService.updatePermissions("ADMIN",
                new RolePermissionsRequest(List.of("PATIENT_READ", "APPOINTMENT_READ")));

        verify(rolePermissionEntityRepository).deleteAll(any(java.lang.Iterable.class));
        verify(rolePermissionEntityRepository).flush();
        verify(rolePermissionEntityRepository, org.mockito.Mockito.times(2)).save(any());
        assertEquals("ADMIN", response.role());
        assertTrue(response.permissions().containsAll(List.of("PATIENT_READ", "APPOINTMENT_READ")));
    }

    @Test
    void getPermissions_returnsPersistedSetSorted() {
        when(rolePermissionEntityRepository.findByRole(com.onecare.backend.enums.Role.DOCTOR))
                .thenReturn(List.of(
                        new RolePermissionEntity(com.onecare.backend.enums.Role.DOCTOR, "PATIENT_READ"),
                        new RolePermissionEntity(com.onecare.backend.enums.Role.DOCTOR, "APPOINTMENT_READ")));

        RolePermissionsResponse response = rolePermissionService.getPermissions("DOCTOR");

        assertEquals(List.of("APPOINTMENT_READ", "PATIENT_READ"), response.permissions());
    }

    @Test
    void getPermissions_unknownRole_throwsNotFound() {
        assertThrows(ResourceNotFoundException.class,
                () -> rolePermissionService.getPermissions("SUPERWIZARD"));
    }
}

