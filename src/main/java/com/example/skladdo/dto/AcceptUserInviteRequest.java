package com.example.skladdo.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** Everything the invitee tells us about themselves, in one go, in exchange for their account. */
public record AcceptUserInviteRequest(
        @NotBlank String token,
        @NotBlank String fullName,
        @NotBlank @Email String email,
        /**
         * Required here, though {@code User.birthDate} is nullable and stays that way: accounts created
         * before this was asked for have no date, and backfilling one would mean inventing it. Requiring
         * it at the point somebody fills the form in is what stops the gap growing.
         */
        @NotNull LocalDate birthDate,
        @NotBlank @Size(min = 8) String password,
        /**
         * An optional profile picture, as a {@code data:image/...;base64,...} URI.
         *
         * <p>Inline rather than through the upload endpoint because the person sending it has no account
         * yet, and opening an unauthenticated upload endpoint to give them one would be a far larger hole
         * than this feature is worth. Travelling inside the accept request means it is gated by exactly
         * the same single-use token as everything else here, and a rejected redemption stores nothing.</p>
         *
         * <p>The client sends the already-cropped 512px square, so this is tens of kilobytes; the server
         * caps it regardless - see {@code UserInviteService.decodeAvatar}.</p>
         */
        String avatarImage
) {
}
