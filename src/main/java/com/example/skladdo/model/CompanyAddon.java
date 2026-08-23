package com.example.skladdo.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;

/**
 * An à-la-carte feature the company has switched on from the in-app add-on store (see {@link AddonType}).
 * One row per ({@link #companyId}, {@link #addonType}); the row's presence - until it lapses at
 * {@link #currentPeriodEnd} when {@link #cancelAtPeriodEnd} is set - is what unlocks the feature.
 * {@code @TenantId}-scoped like the rest of a company's data.
 */
@Entity
@Table(
        name = "company_addon",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_company_addon_company_type",
                columnNames = {"company_id", "addon_type"}
        )
)
@Getter
@Setter
public class CompanyAddon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "addon_type", nullable = false, length = 32)
    private AddonType addonType;

    private Instant activatedAt;

    /** End of the current paid period; the add-on renews (or, if cancelled, lapses) at this instant. */
    private Instant currentPeriodEnd;

    @Column(nullable = false)
    private boolean cancelAtPeriodEnd = false;
}
