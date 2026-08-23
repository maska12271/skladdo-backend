package com.example.skladdo.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Creating an invite link. Everything but the label is optional: a link with no terms at all is simply a
 * signup URL whose arrivals are attributed to it, which is a perfectly good thing to want.
 *
 * @param accountType   pin the signup to BUSINESS or WAREHOUSE, or blank to let the visitor choose
 * @param plan          the paid tier to start on, or blank to let the visitor choose
 * @param freeDays      days of free use granted on signup (the 30/60/90 presets are a UI convenience -
 *                      any positive number is accepted)
 * @param expiresInDays how long the link itself stays usable; blank means it never expires
 * @param maxUses       how many signups it allows; blank means unlimited
 */
public record CreateInviteLinkRequest(
        @NotBlank String label,
        String accountType,
        String plan,
        @Min(1) Integer freeDays,
        @Min(1) Integer expiresInDays,
        @Min(1) Integer maxUses
) {
}
