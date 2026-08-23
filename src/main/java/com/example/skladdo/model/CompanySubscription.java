package com.example.skladdo.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;

/**
 * The calling company's subscription: which {@link PlanType} it is on, the billing status, and the
 * current billing period. Exactly one row per company, created lazily with a Starter trial on first
 * access - the same migration-friendly pattern as {@link CompanySettings}, so companies that predate
 * this feature work without a migration. {@code @TenantId}-scoped so each company only sees its own.
 *
 * <p>Billing is not wired to a payment provider yet: plan changes take effect immediately and
 * {@link #currentPeriodEnd} doubles as the "next payment" date. The rollover at period end is evaluated
 * lazily on read and by a nightly sweep - see {@code PlanService} and {@code ScheduledMaintenanceService}.</p>
 */
@Entity
@Getter
@Setter
public class CompanySubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PlanType plan = PlanType.STARTER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SubscriptionStatus status = SubscriptionStatus.TRIALING;

    /** When the free trial ends; null once converted to a paid subscription. */
    private Instant trialEndsAt;

    /** Start of the current billing period. */
    private Instant currentPeriodStart;

    /**
     * End of the current billing period; also the date the next payment would be taken (or, when
     * {@link #cancelAtPeriodEnd} is set, the date access ends and the subscription lapses to
     * {@link SubscriptionStatus#EXPIRED}). Null once the subscription has lapsed.
     */
    private Instant currentPeriodEnd;

    /** When true, the subscription is not renewed at {@link #currentPeriodEnd} and lapses (EXPIRED). */
    @Column(nullable = false)
    private boolean cancelAtPeriodEnd = false;

    private Instant createdAt;
}
