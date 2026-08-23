package com.example.skladdo.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * An invitation issued by a {@link CompanyType#BUSINESS} company for a {@link CompanyType#WAREHOUSE}
 * account to start working inside it. Redeeming one creates a live {@link WarehouseConnection} straight
 * away - <strong>the code is the consent</strong>, so there is no request to approve afterwards.
 *
 * <p>That is what makes the short lifetime and the single use load-bearing rather than cosmetic: handing
 * the code over is the moment of granting access, so a code must not sit around in a mailbox staying
 * usable. It expires {@value #VALID_HOURS} hours after it is issued, is spent the first time it works, and
 * can be reissued at any point (which abandons the previous one).</p>
 *
 * <p><strong>Not {@code @TenantId}-scoped.</strong> It is written by one company and read by another, so a
 * discriminator would hide it from the side that needs to redeem it; the redeem path looks it up by the
 * secret itself and every other method names the company it is querying for.</p>
 */
@Entity
@Table(
        name = "connection_code",
        indexes = @Index(name = "idx_conn_code_client", columnList = "client_company_id")
)
@Getter
@Setter
public class ConnectionCode {

    /** How long a freshly issued code stays usable. */
    public static final int VALID_HOURS = 48;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The business company that issued it - the company a redeemer gains access to. */
    @Column(name = "client_company_id", nullable = false, updatable = false)
    private Long clientCompanyId;

    @Column(nullable = false, unique = true, length = 32, updatable = false)
    private String code;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    // NOT updatable = false: issueCode() rewrites this on every outstanding code to abandon it early
    // when a fresh code is drawn (see the class javadoc). That write would silently no-op otherwise.
    @Column(nullable = false)
    private Instant expiresAt;

    /** Set the moment it is redeemed, which is also what spends it. */
    private Instant redeemedAt;

    /** The warehouse company that redeemed it, kept so the issuer can see who used their code. */
    private Long redeemedByCompanyId;

    private Long createdByUserId;

    public boolean isRedeemed() {
        return redeemedAt != null;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /** True while the code would still work: issued, not yet spent, not yet expired. */
    public boolean isUsable(Instant now) {
        return !isRedeemed() && !isExpired(now);
    }
}
