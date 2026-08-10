package com.example.kladdo.repository;

import com.example.kladdo.model.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByToken(String token);

    /** Invalidates any tokens a user still holds, so issuing a new link supersedes the old one. */
    @Modifying
    @Transactional
    void deleteByUserId(Long userId);
}
