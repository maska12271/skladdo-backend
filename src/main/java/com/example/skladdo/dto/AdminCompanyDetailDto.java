package com.example.skladdo.dto;

import java.time.Instant;
import java.util.List;

/**
 * Everything the operator's company page shows: the list row it came from, plus the subscription's
 * billing period and the company's user accounts.
 *
 * <p>The users are a deliberately narrow projection ({@link Member}) rather than {@code UserDto}: this is
 * a cross-tenant view, so it carries only what an operator needs to answer a support question - who is in
 * here, what can they do, and can they still get in.</p>
 */
public record AdminCompanyDetailDto(
        AdminCompanyDto company,
        Instant currentPeriodStart,
        Boolean cancelAtPeriodEnd,
        List<Member> users
) {
    public record Member(
            Long id,
            String email,
            String fullName,
            String role,
            Boolean active,
            Boolean archived,
            /** True while the account still has to set its own password from an emailed link. */
            Boolean passwordSetupPending,
            Instant lastLoginAt
    ) {
    }
}
