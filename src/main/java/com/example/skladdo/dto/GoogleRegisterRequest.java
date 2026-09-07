package com.example.skladdo.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Self-service signup where the owner proves who they are with Google instead of choosing a password.
 *
 * <p>Everything a new company needs that Google cannot supply is still asked for - the company's name, the
 * account type, the plan - so this is {@link RegisterRequest} minus the three fields the verified ID token
 * answers: the owner's email, their name, and a password they now do not need. Those are read from the
 * token after verification and never from the request.</p>
 */
public record GoogleRegisterRequest(

        /** The ID token from the Google button; see {@link GoogleLoginRequest}. */
        @NotBlank String idToken,

        @NotBlank String companyName,

        /** One of the paid tiers, required for a {@code BUSINESS} signup - see {@link RegisterRequest#plan}. */
        String plan,

        /** {@code BUSINESS} or {@code WAREHOUSE} - see {@link RegisterRequest#accountType}. */
        String accountType,

        /** An operator-issued invite code - see {@link RegisterRequest#inviteCode}. */
        String inviteCode,

        /** The paid extras switched on at signup - see {@link RegisterRequest#addons}. */
        List<String> addons
) {
}
