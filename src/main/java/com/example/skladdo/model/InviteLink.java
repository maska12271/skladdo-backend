package com.example.skladdo.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A signup link issued by the platform operator that carries its own terms: which plan the new company
 * starts on, how long it gets free, and how long the link itself stays usable.
 *
 * <p>Self-service signup already exists and is open to anyone, so this is <strong>not</strong> a way in -
 * it is a way in <em>on particular terms</em>. Someone who never sees a link can still sign up and pay;
 * someone who follows one gets whatever the operator promised them, without the operator having to touch
 * the company afterwards.</p>
 *
 * <p><strong>Not {@code @TenantId}-scoped.</strong> It belongs to the platform, not to a company, and it
 * is read by an unauthenticated visitor who has no tenant at all - the same reason
 * {@link ConnectionCode} is not scoped either.</p>
 *
 * <p>Links are <strong>revoked, never deleted</strong>. A company records the link it arrived through
 * ({@code Company.inviteLinkId}), and deleting the row would silently erase where a customer came
 * from.</p>
 */
@Entity
@Table(name = "invite_link", indexes = @Index(name = "idx_invite_link_code", columnList = "code"))
@Getter
@Setter
public class InviteLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The secret in the URL ({@code /register?invite=<code>}). */
    @Column(nullable = false, unique = true, length = 40, updatable = false)
    private String code;

    /** What this link is for, in the operator's words - "Autumn outreach", "Acme pilot". */
    @Column(nullable = false, length = 200)
    private String label;

    /**
     * The account type a signup through this link must use, or {@code null} to let the visitor choose.
     * Pinning it is what makes a link for one specific customer feel like an invitation rather than a
     * form.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CompanyType accountType;

    /**
     * The plan a signup through this link starts on, or {@code null} to let the visitor pick as usual.
     * Only ever a selectable (paid) tier - the free warehouse tier follows from the account type and is
     * never something a link grants.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private PlanType plan;

    /**
     * How many days of free use a company gets on signing up through this link, or {@code null} for none.
     * Converted to a concrete {@code Company.freeUntil} at signup, so changing the link afterwards never
     * retroactively alters what an existing customer was promised.
     */
    private Integer freeDays;

    /** When the link stops working, or {@code null} for no expiry. */
    private Instant expiresAt;

    /** How many signups the link allows in total, or {@code null} for no limit. */
    private Integer maxUses;

    @Column(nullable = false)
    private int usedCount = 0;

    /** Cleared to revoke the link. Kept rather than deleted so attribution survives - see the class note. */
    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Long createdByUserId;

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public boolean isExhausted() {
        return maxUses != null && usedCount >= maxUses;
    }

    /** True while a signup through this link would still be honoured. */
    public boolean isUsable(Instant now) {
        return active && !isExpired(now) && !isExhausted();
    }
}
