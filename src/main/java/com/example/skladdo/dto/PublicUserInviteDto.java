package com.example.skladdo.dto;

import java.time.Instant;

/**
 * What the join page is told about a link before anyone has an account.
 *
 * <p>Only the company name and the deadline: enough to greet the visitor by the company that invited
 * them and to say how long they have. Nothing about the role, the permissions or who issued it - a link
 * that leaked should not also describe the access behind it. Anything unusable answers
 * {@code valid = false} without saying whether it was wrong, spent, withdrawn or simply too late.</p>
 */
public record PublicUserInviteDto(
        boolean valid,
        String companyName,
        Instant expiresAt
) {
    public static PublicUserInviteDto invalid() {
        return new PublicUserInviteDto(false, null, null);
    }
}
