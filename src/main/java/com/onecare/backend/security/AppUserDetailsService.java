package com.onecare.backend.security;

import com.onecare.backend.entity.User;
import com.onecare.backend.repository.UserRepository;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public AppUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        boolean accountNonLocked =
                user.getLockedUntil() == null ||
                user.getLockedUntil().isBefore(LocalDateTime.now());

        Set<GrantedAuthority> authorities = new HashSet<>();

        // Role authority
        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));

        // Permission authorities
        RolePermission.getPermissions(user.getRole())
                .forEach(permission ->
                        authorities.add(
                                new SimpleGrantedAuthority(permission)
                        )
                );

        return new AppUserDetails(
                user.getUserId(),
                user.getUsername(),
                user.getPasswordHash(),
                Boolean.TRUE.equals(user.getIsActive()),
                true,
                true,
                accountNonLocked,
                authorities
        );
    }

    public static class AppUserDetails implements UserDetails {
        private final Long userId;
        private final String username;
        private final String password;
        private final boolean enabled;
        private final boolean accountNonExpired;
        private final boolean credentialsNonExpired;
        private final boolean accountNonLocked;
        private final Collection<? extends GrantedAuthority> authorities;

        public AppUserDetails(
                Long userId, String username,
                String password, boolean enabled,
                boolean accountNonExpired, boolean credentialsNonExpired,
                boolean accountNonLocked, Collection<? extends GrantedAuthority> authorities) {

            this.userId = userId;
            this.username = username;
            this.password = password;
            this.enabled = enabled;
            this.accountNonExpired = accountNonExpired;
            this.credentialsNonExpired = credentialsNonExpired;
            this.accountNonLocked = accountNonLocked;
            this.authorities = authorities;
        }

        public Long getUserId() {
            return userId;
        }

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return authorities;
        }

        @Override
        public String getPassword() {
            return password;
        }

        @Override
        public String getUsername() {
            return username;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public boolean isAccountNonExpired() {
            return accountNonExpired;
        }

        @Override
        public boolean isCredentialsNonExpired() {
            return credentialsNonExpired;
        }

        @Override
        public boolean isAccountNonLocked() {
            return accountNonLocked;
        }
    }
}