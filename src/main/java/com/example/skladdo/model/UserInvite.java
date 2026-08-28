package com.example.skladdo.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A single-use link that lets one person create their own account inside a company.
 *
 * <p>Inverts who fills the form. An administrator no longer types a colleague's name and address and
 * guesses at the spelling - they decide the one thing that is theirs to decide, the access the account
 * gets, and hand over a link. The person on the other end enters their own details, which is both more
 * accurate and the only way to ask for something like a date of birth without it being a company
 * recording a fact about someone behind their back.</p>
 *
 * <p><strong>Not {@code @TenantId}-scoped</strong>, unlike almost everything else. The link is opened by
 * a visitor with no account and therefore no tenant, so the row has to be findable by token alone - the
 * company it belongs to is what the row <em>tells</em> the redemption, not something already known. Every
 * administrator-facing lookup therefore filters on {@link #companyId} explicitly. Same reason
 * {@link InviteLink} and {@link ConnectionCode} are unscoped.</p>
 *
 * <p>Rows are kept after they are used or revoked rather than deleted: an invitation is a grant of access
 * and who issued it, to whom, and when it was taken up is worth being able to answer later.</p>
 */
@Entity
@Table(name = "user_invite", indexes = @Index(name = "idx_user_invite_token", columnList = "token"))
@Getter
@Setter
public class UserInvite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The company the invitee will join. A plain column, not {@code @TenantId} - see the class note. */
    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** The secret in the URL ({@code /join?token=<token>}). */
    @Column(nullable = false, unique = true, length = 64, updatable = false)
    private String token;

    /**
     * The role the account is created with. Never {@link Role#OWNER}: a company has one, and it is not
     * something a link can hand out.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role = Role.USER;

    /** Price visibility for the restricted roles, exactly as on the create-user form. */
    @Column(nullable = false)
    private boolean canSeePrices = true;

    /**
     * The per-module access to grant, encoded by {@code UserInviteService}, or {@code null} to fall back
     * to the company's own default template (which is what an administrator who did not touch the
     * permission editor means).
     *
     * <p>Encoded into one column rather than given a child table: these rows live for three days and are
     * never queried by permission, so a table to join would buy nothing. {@code User.mutedNotificationTypes}
     * packs a small set the same way.</p>
     */
    @Column(length = 2000)
    private String permissions;

    /** When the link stops working. Always set - an invitation with no end is a standing way in. */
    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(updatable = false)
    private Long createdByUserId;

    /** The address the link was last emailed to, or {@code null} if it was only ever copied by hand. */
    @Column(length = 255)
    private String sentToEmail;

    private Instant sentAt;

    /** When the invitation was taken up. Non-null means spent: one link, one account. */
    private Instant acceptedAt;

    /** The account that came out of it, for the trail. */
    private Long acceptedUserId;

    /** Set to withdraw an outstanding invitation before anyone uses it. */
    @Column(nullable = false)
    private boolean revoked = false;

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isAccepted() {
        return acceptedAt != null;
    }

    /** True while following this link would still create an account. */
    public boolean isRedeemable(Instant now) {
        return !revoked && !isAccepted() && !isExpired(now);
    }
}
