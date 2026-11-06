package com.onecare.backend.repository;

import com.onecare.backend.entity.User;
import com.onecare.backend.enums.Role;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    /** Active doctors ordered by display name — the public booking directory. */
    List<User> findByRoleAndIsActiveTrueOrderByUsernameAsc(Role role);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.userId = :userId")
    Optional<User> findByIdForUpdate(@Param("userId") Long userId);

    boolean existsByUsername(String username);
    
    boolean existsByEmail(String email);

    @Modifying
    @Query(value = """
    UPDATE users
    SET failed_attempts = COALESCE(failed_attempts, 0) + 1
    WHERE user_id = :userId
    """, nativeQuery = true)
    int recordFailedAttempt(@Param("userId") Long userId);
}