package com.example.skladdo.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
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
     * Whether this account still has to set its own password (via an emailed link) before it can sign
     * in. Set to {@code true} for a freshly invited user - who is created without a usable password -
     * and cleared once they complete the reset. Blocks login while {@code true}.
     *
     * <p>Nullable so the column adds cleanly to an existing {@code app_user} table under
     * {@code ddl-auto=update}: rows that predate this feature read back {@code null}, which is treated
     * as "not pending" (they already have a real password), so every current account keeps signing in
     * unchanged - same migration-friendly pattern as {@link #canSeePrices}.</p>
     */
    private Boolean passwordSetupPending = false;

    /**
     * Personal HTML email signature, appended to every manufacturer email this user sends. Optional and
     * edited by the user themselves (not an admin). Nullable so the column adds cleanly under
     * {@code ddl-auto=update}.
     */
    @Column(length = 5000)
    private String emailSignature;

    /**
     * Comma-separated {@link NotificationType} names this user has switched off, e.g.
     * {@code "LOW_STOCK,TENDER_DEADLINE"}. A plain column rather than a preferences table: the set is tiny
     * and only ever read/written whole. Nullable (and null/blank = nothing muted) so it adds cleanly under
     * {@code ddl-auto=update} - the same pattern as {@link #canSeePrices}.
     */
    @Column(length = 500)
    private String mutedNotificationTypes;

    /**
     * This account's interface language ({@code "en"}/{@code "et"}/{@code "ru"}), seeded from the
     * company's {@code defaultUserLanguage} when the account is created and changeable by the user
     * themselves. Also picks the language of their invitation email.
     *
     * <p>Nullable: accounts that predate this column read back {@code null}, and the client then keeps
     * whatever language the browser was already using - same migration-friendly pattern as
     * {@link #canSeePrices}.</p>
     */
    @Column(length = 5)
    private String language;

    /**
     * Whether this account operates the platform itself (the Skladdo admin panel) rather than a company.
     * It is a <em>system</em> capability, not a company role: it reads and administers every tenant, so
     * nothing inside the application may grant it. The flag is reconciled at startup from the
     * {@code app.platform-admin-emails} property - see {@code PlatformAdminBootstrap} - which makes the
     * deployment's configuration the only way in and the only way out.
     *
     * <p>Nullable so the column adds cleanly under {@code ddl-auto=update}; every existing account reads
     * back {@code null}, which is treated as "not a platform admin" - the same migration-friendly pattern
     * as {@link #canSeePrices}.</p>
     */
    private Boolean platformAdmin = false;

    /** True only for an account explicitly marked as a platform operator. */
    public boolean isPlatformAdmin() {
        return Boolean.TRUE.equals(platformAdmin);
    }

    /**
     * When this account last signed in successfully, or {@code null} if it never has (or last did so
     * before this column existed). Written outside the login transaction, which is read-only - see
     * {@code AuthController.login}.
     *
     * <p>This is what the admin panel's "companies active in the last N months" figure counts: a company
     * is active when any of its users has signed in since the cutoff.</p>
     */
    private Instant lastLoginAt;

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
