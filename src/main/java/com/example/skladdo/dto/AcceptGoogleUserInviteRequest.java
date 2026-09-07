package com.example.skladdo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Redeeming an invitation with Google instead of a chosen password.
 *
 * <p>{@link AcceptUserInviteRequest} minus the three fields a verified ID token answers: the name, the
 * address, and a password that is no longer needed. Those are read from the token after it is verified and
 * never from this request — an invitation admits one person, and letting the payload name a different
 * address would make it admit anybody.</p>
 */
public record AcceptGoogleUserInviteRequest(

        /** The invitation token from the {@code /join?token=...} link. */
        @NotBlank String token,

        /** The ID token from the Google button — see {@link GoogleLoginRequest}. */
        @NotBlank String idToken,

        /**
         * Required, and theirs to give — Google does not supply it, which is why this is the one field the
         * form still asks for. See {@link AcceptUserInviteRequest#birthDate} for why the column itself
         * stays nullable.
         */
        @NotNull LocalDate birthDate,

        /** Optional profile picture as a data URI — see {@link AcceptUserInviteRequest#avatarImage}. */
        String avatarImage
) {
}
