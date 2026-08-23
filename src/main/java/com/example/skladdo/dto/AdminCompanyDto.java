package com.example.skladdo.dto;

import java.time.Instant;

/**
 * One company as the platform operator sees it: its identity, what it pays for, how big it is and
 * whether anyone still uses it. Assembled by {@code AdminService} from several sources - the company row,
 * its (tenant-scoped) subscription, and its users - rather than mapped from a single entity.
 *
 * @param status the derived operational state; see {@code AdminService.deriveStatus}
 */
public record AdminCompanyDto(
        Long id,
        String name,
        String registrationCode,
        /** BUSINESS or WAREHOUSE - see {@link com.example.skladdo.model.CompanyType}. */
        String type,
        /** ACTIVE, SPONSORED, SUSPENDED or OVERDUE. */
        String status,
        /** The subscription's plan, or null when the company has no subscription row yet. */
        String plan,
        /** The raw subscription status (ACTIVE/EXPIRED/...), kept alongside the derived one. */
        String subscriptionStatus,
        Instant currentPeriodEnd,
        long userCount,
        /** Null for companies created before creation dates were recorded - reported as unknown, not guessed. */
        Instant createdAt,
        /** The most recent sign-in by anyone in this company; null if nobody ever has. */
        Instant lastActiveAt,
        String ownerName,
        String ownerEmail,
        /** End of the company's free period, or null if it pays normally. Past dates mean it has lapsed. */
        Instant freeUntil,
        /** The operator's note on why the free period was granted. Never shown to the company. */
        String freeNote,
        /** The invite link this company signed up through, or null. */
        Long inviteLinkId
) {
}
