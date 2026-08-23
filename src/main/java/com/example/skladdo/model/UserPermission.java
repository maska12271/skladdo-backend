package com.example.skladdo.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * Per-user, per-module access flags. One row per ({@link User}, {@link PermissionModule}) pair.
 *
 * <p>Only {@link Role#USER} accounts carry permission rows; owners and administrators are full-access
 * by virtue of their role and have no rows here. A missing row for a module means "no access".</p>
 *
 * <p>Like {@link User}, this entity is intentionally not {@code @TenantId}-scoped: it is read during
 * authorization (before/independent of the tenant filter) and is always reached through a user that
 * has already been company-scoped in the service layer.</p>
 */
@Entity
@Table(
        name = "user_permission",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_user_permission_user_module",
                columnNames = {"user_id", "module"}
        )
)
@Getter
@Setter
public class UserPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "module", nullable = false)
    private PermissionModule module;

    @Column(nullable = false)
    private boolean canView = false;

    @Column(nullable = false)
    private boolean canCreate = false;

    @Column(nullable = false)
    private boolean canEdit = false;

    @Column(nullable = false)
    private boolean canDelete = false;
}
