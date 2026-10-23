package com.onecare.backend.dto.request;

import java.util.List;

/**
 * Replacement permission set for a role (FR003, UC3). The set must not be
 * empty (BR006) and is validated against the permission catalog and the
 * BR004-BR006 hard rules before anything is persisted.
 */
public record RolePermissionsRequest(List<String> permissions) {
}
