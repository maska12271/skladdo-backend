package com.example.skladdo.dto;

/**
 * What the signup page is told about an invite code, before anyone has authenticated.
 *
 * <p>Deliberately thin. An unauthenticated caller can try any code, so this must reveal nothing about the
 * platform beyond the terms of the link they already hold - no label (the operator's own words, e.g. a
 * customer's name), no usage counts, no expiry date, and nothing at all when the code does not work. An
 * invalid and a revoked code are equally just {@code valid = false}.</p>
 *
 * @param accountType the type the signup is pinned to, or null if the visitor still chooses
 * @param plan        the plan the signup is pinned to, or null if the visitor still chooses
 * @param freeDays    days of free use this link grants, or null for none
 */
public record PublicInviteDto(
        boolean valid,
        String accountType,
        String plan,
        Integer freeDays
) {
    public static PublicInviteDto invalid() {
        return new PublicInviteDto(false, null, null, null);
    }
}
