package com.onecare.backend.security;

import com.onecare.backend.entity.User;
import com.onecare.backend.repository.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

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

        boolean accountNonLocked = user.getLockedUntil() == null || user.getLockedUntil().isBefore(LocalDateTime.now());

        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPasswordHash(),
                Boolean.TRUE.equals(user.getIsActive()),
                true,
                true,
                accountNonLocked,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
    }
}