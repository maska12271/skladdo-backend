package com.example.kladdo.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

/**
 * A login account belonging to a {@link Company}. Email is unique across the whole
 * system so it can be used as the login identifier before a tenant is known.
 *
 * <p>This entity is intentionally <em>not</em> tenant-scoped via {@code @TenantId} - it must be
 * loadable during authentication (before the current company is established). User listings are
 * scoped to the caller's company manually in the service layer.</p>
 */
@Entity
@Table(name = "app_user")
@Getter
@Setter
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Email
    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.USER;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    /**
     * Whether this account may see monetary values (prices, order totals, revenue). Configurable
     * mainly for {@link Role#WAREHOUSE} staff who fulfil orders and keep stock without needing price
     * visibility. Managers and regular users default to {@code true}.
     *
     * <p>Left nullable so the column can be added to an existing {@code app_user} table under
     * {@code ddl-auto=update} without a default; a {@code null} value is treated as "can see prices".</p>
     */
    private Boolean canSeePrices = true;

    private Boolean active = true;

    private Boolean archived = false;

    /**
     * Warehouses this account is assigned to. Only relevant for {@link Role#WAREHOUSE} (and
     * potentially {@link Role#USER}) accounts. Managers ({@link Role#OWNER}, {@link Role#ADMINISTRATOR})
     * bypass this filter and see all warehouses without needing explicit assignments.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_warehouse",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "warehouse_id")
    )
    private Set<Warehouse> warehouses = new HashSet<>();
}
