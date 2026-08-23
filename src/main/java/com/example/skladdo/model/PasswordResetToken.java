package com.example.skladdo.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A single-use, time-limited token that lets a {@link User} set (or reset) their own password through
 * the public reset page. Issued when an admin invites a user or when someone uses "forgot password".
 *
 * <p>Deliberately <em>not</em> {@code @TenantId}-scoped (like {@link User}): the public reset flow
 * looks a token up before any company/tenant context exists. Issuing a fresh token invalidates any
 * earlier unused ones for the same user (see {@code PasswordResetTokenRepository.deleteByUserId}).</p>
 */
@Entity
@Table(name = "password_reset_token")
@Getter
@Setter
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The opaque URL-safe token embedded in the reset link. Unique so a link maps to exactly one row. */
    @Column(nullable = false, unique = true)
    private String token;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private Instant expiresAt;

    /** When the token was redeemed. {@code null} means still unused; a non-null value blocks reuse. */
    private Instant usedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** A token is redeemable only while it is unused and not past its expiry. */
    public boolean isRedeemable() {
        return usedAt == null && expiresAt.isAfter(Instant.now());
    }
}
