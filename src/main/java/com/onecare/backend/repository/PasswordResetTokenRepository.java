package com.onecare.backend.repository;

import com.onecare.backend.entity.PasswordResetToken;
import com.onecare.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    List<PasswordResetToken> findByUserAndUsedFalse(User user);

    @Modifying
    @Query("update PasswordResetToken t set t.used = true, t.usedAt = :now where t.user = :user and t.used = false")
    int invalidateActiveTokens(@Param("user") User user, @Param("now") LocalDateTime now);
}
