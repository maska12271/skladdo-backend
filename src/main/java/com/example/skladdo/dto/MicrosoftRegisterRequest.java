package com.example.skladdo.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Self-service signup where the owner proves who they are with Microsoft instead of choosing a password.
 * See {@link GoogleRegisterRequest}, whose reasoning is identical here.
 */
public record MicrosoftRegisterRequest(

        /** The ID token from MSAL; see {@link MicrosoftLoginRequest}. */
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
