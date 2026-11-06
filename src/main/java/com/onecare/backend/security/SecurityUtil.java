package com.onecare.backend.security;

import com.onecare.backend.enums.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Arrays;
import java.util.Optional;

public final class SecurityUtil {

    private SecurityUtil() {
        // Utility class
    }

    // Get the current Spring Security authentication.
    public static Optional<Authentication> getAuthentication() {
        return Optional.ofNullable(
                SecurityContextHolder.getContext().getAuthentication()
        );
    }

    // Get the current authenticated principal as UserDetails.
    public static Optional<UserDetails> getPrincipal() {
        return getAuthentication()
                .map(Authentication::getPrincipal)
                .filter(UserDetails.class::isInstance)
                .map(UserDetails.class::cast);
    }

    //Get the current authenticated user's ID.
    public static Optional<Long> getCurrentUserId() {
        return getPrincipal()
                .filter(AppUserDetailsService.AppUserDetails.class::isInstance)
                .map(AppUserDetailsService.AppUserDetails.class::cast)
                .map(AppUserDetailsService.AppUserDetails::getUserId);
    }

    // Get the current authenticated user's username.
    public static Optional<String> getCurrentUsername() {
        return getPrincipal()
                .map(UserDetails::getUsername);
    }

    // Get the current authenticated user's role.
    public static Optional<Role> getCurrentUserRole() {
        return getAuthentication()
                .flatMap(authentication ->
                        authentication.getAuthorities().stream()
                                .map(authority -> authority.getAuthority())
                                .filter(authority -> authority.startsWith("ROLE_"))
                                .map(authority -> authority.substring(5))
                                .map(SecurityUtil::toRole)
                                .filter(Optional::isPresent)
                                .map(Optional::get)
                                .findFirst()
                );
    }

    /**
     * Check whether the current user has the specified role.
     *
     * Example:
     * SecurityUtil.hasRole("ADMIN")
     */
    public static boolean hasRole(String role) {
        if (role == null || role.isBlank()) {
            return false;
        }

        String requiredAuthority = "ROLE_" + role;

        return getAuthentication()
                .map(authentication ->
                        authentication.getAuthorities().stream()
                                .anyMatch(authority ->
                                        requiredAuthority.equals(
                                                authority.getAuthority()
                                        )
                                )
                )
                .orElse(false);
    }

    /**
     * Check whether the current user has at least one
     * of the specified roles.
     *
     * Example:
     * SecurityUtil.hasAnyRole("ADMIN", "SUPER_ADMIN")
     */
    public static boolean hasAnyRole(String... roles) {
        if (roles == null || roles.length == 0) {
            return false;
        }

        return getAuthentication()
                .map(authentication ->
                        authentication.getAuthorities().stream()
                                .map(authority -> authority.getAuthority())
                                .anyMatch(authority ->
                                        Arrays.stream(roles)
                                                .filter(role ->
                                                        role != null
                                                                && !role.isBlank()
                                                )
                                                .anyMatch(role ->
                                                        authority.equals(
                                                                "ROLE_" + role
                                                        )
                                                )
                                )
                )
                .orElse(false);
    }

    /**
     * Check whether the current user has the specified permission.
     *
     * Example:
     * SecurityUtil.hasPermission(Permission.USER_CREATE)
     */
    public static boolean hasPermission(String permission) {
        if (permission == null || permission.isBlank()) {
            return false;
        }

        return getAuthentication()
                .map(authentication ->
                        authentication.getAuthorities().stream()
                                .anyMatch(authority ->
                                        permission.equals(
                                                authority.getAuthority()
                                        )
                                )
                )
                .orElse(false);
    }

    /**
     * Check whether the current user has at least one
     * of the specified permissions.
     *
     * Example:
     * SecurityUtil.hasAnyPermission(
     *     Permission.USER_CREATE,
     *     Permission.USER_UPDATE
     * )
     */
    public static boolean hasAnyPermission(String... permissions) {
        if (permissions == null || permissions.length == 0) {
            return false;
        }

        return getAuthentication()
                .map(authentication ->
                        authentication.getAuthorities().stream()
                                .map(authority -> authority.getAuthority())
                                .anyMatch(authority ->
                                        Arrays.stream(permissions)
                                                .anyMatch(authority::equals)
                                )
                )
                .orElse(false);
    }

    // Check whether the current user is a SUPER_ADMIN.
    public static boolean isSuperAdmin() {
        return hasRole("SUPER_ADMIN");
    }

    // Check whether the current user is an ADMIN.
    public static boolean isAdmin() {
        return hasRole("ADMIN");
    }

    // Check whether the current user is a DOCTOR.
    public static boolean isDoctor() {
        return hasRole("DOCTOR");
    }

    // Check whether the current user is a PHARMACIST.
    public static boolean isPharmacist() {
        return hasRole("PHARMACIST");
    }

    // Check whether a user is authenticated with a real application principal.

    public static boolean isAuthenticated() {
        return getAuthentication()
                .filter(Authentication::isAuthenticated)
                .map(Authentication::getPrincipal)
                .filter(principal ->
                        !(principal instanceof String stringPrincipal)
                                || !"anonymousUser".equals(stringPrincipal)
                )
                .isPresent();
    }

    // Convert a String to Role safely.
    private static Optional<Role> toRole(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.of(Role.valueOf(roleName));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}

