package com.example.skladdo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Redeeming an invitation with Microsoft instead of a chosen password. See
 * {@link AcceptGoogleUserInviteRequest}, whose reasoning is identical here.
 */
public record AcceptMicrosoftUserInviteRequest(

        /** The invitation token from the {@code /join?token=...} link. */
        @NotBlank String token,

        /** The ID token from MSAL; see {@link MicrosoftLoginRequest}. */
        @NotBlank String idToken,

        /** Required, and theirs to give - Microsoft does not supply it. */
        @NotNull LocalDate birthDate,

        /** Optional profile picture as a data URI - see {@link AcceptUserInviteRequest#avatarImage}. */
        String avatarImage
) {
}
