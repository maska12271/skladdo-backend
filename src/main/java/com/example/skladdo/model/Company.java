package com.example.skladdo.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A tenant. Every business entity carries the id of the company it belongs to
 * (see Hibernate {@code @TenantId} columns), so data is fully isolated per company.
 */
@Entity
@Getter
@Setter
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false)
    private String name;

    @Column(unique = true)
    private String registrationCode;

    /**
     * Whether this company may be used at all. Cleared by the platform operator to suspend an account
     * (see {@code AdminService}); enforced at authentication time, not merely in the UI - a suspended
     * company's users fail {@code CustomUserDetails.isEnabled()}, so existing tokens stop working on
     * their next request rather than at expiry.
     */
    private Boolean active = true;

    /** Whether this company is usable, reading a legacy null as active. */
    public boolean isActive() {
        return !Boolean.FALSE.equals(active);
    }

    /**
     * When this company was created. Nullable so the column adds cleanly under {@code ddl-auto=update};
     * companies that predate it are backfilled from their subscription's creation date where one exists
     * (see {@code SchemaMigrations.backfillCompanyCreatedAt}) and otherwise read back {@code null}, which
     * the admin panel reports as unknown rather than guessing.
     */
    private Instant createdAt;

    /**
     * Until when this company uses Skladdo free of charge, or {@code null} if it pays normally.
     *
     * <p>A platform decision rather than a subscription state, which is why it lives here and not on
     * {@link CompanySubscription}: the operator grants it from outside the tenant, the company cannot
     * change it, and it must be readable across every tenant without the {@code @TenantId} discriminator
     * getting in the way. The company keeps whatever plan it is on - a sponsorship says "do not bill
     * this", not "give them different limits".</p>
     *
     * <p>Always an end date, never open-ended. A comp that nobody is ever prompted about is one that
     * quietly becomes permanent; an end date is what lets the panel warn before it lapses.</p>
     */
    private Instant freeUntil;

    /** Why the free period was granted - the operator's own note, never shown to the company. */
    @Column(length = 500)
    private String freeNote;

    /** True while a free period is in force. */
    public boolean isSponsored() {
        return freeUntil != null && Instant.now().isBefore(freeUntil);
    }

    /**
     * The invite link this company signed up through, or {@code null} for an ordinary signup or a company
     * the operator created by hand. A plain informational id rather than a mapped relation, matching
     * {@code SentEmail.templateId}: it records where a customer came from and must not become a foreign
     * key that constrains what can be done to the link later.
     */
    private Long inviteLinkId;

    /**
     * Which half of the app this company lives in. Chosen at signup and never editable afterwards - it is
     * a security boundary, not a preference. Nullable in the database so the column adds cleanly under
     * {@code ddl-auto=update}; every company that predates the split is a {@link CompanyType#BUSINESS} one,
     * which is what {@link #getType()} returns for null.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, updatable = false)
    private CompanyType type = CompanyType.BUSINESS;

    /** This company's kind, reading the pre-split null as {@link CompanyType#BUSINESS}. */
    public CompanyType getType() {
        return type == null ? CompanyType.BUSINESS : type;
    }

    /** True when this company only works inside its clients' companies and owns no business data itself. */
    public boolean isWarehouseAccount() {
        return getType() == CompanyType.WAREHOUSE;
    }

    /** True when this company is Skladdo's own operator shell rather than a customer. */
    public boolean isPlatformCompany() {
        return getType() == CompanyType.PLATFORM;
    }

    /**
     * True when this company has no catalogue, orders or tenders of its own - a warehouse account, which
     * works only inside its clients, or the platform shell, which runs the service.
     */
    public boolean ownsNoBusinessData() {
        return !getType().ownsBusinessData();
    }
}
