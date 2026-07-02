package com.example.kladdo.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

/**
 * Company-wide template for the per-module access a newly created {@link Role#USER} starts with. One
 * row per ({@code company}, {@link PermissionModule}) pair; copied into a user's {@link UserPermission}
 * rows when the account is created (see {@code PermissionService}).
 *
 * <p>Unlike {@link UserPermission} - which is reached through an already company-scoped user and is
 * therefore not tenant-filtered - this template is a company-level setting, so it is {@code @TenantId}
 * scoped directly.</p>
 */
@Entity
@Table(
        name = "default_user_permission",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_default_user_permission_company_module",
                columnNames = {"company_id", "module"}
        )
)
@Getter
@Setter
public class DefaultUserPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

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
