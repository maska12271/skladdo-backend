package com.example.kladdo.dto;

/**
 * Tells the reset page whether a token is still usable and, if so, which account it belongs to (so it
 * can greet the user). Never reveals anything for an invalid/expired/used token.
 */
public record PasswordResetInfoResponse(
        boolean valid,
        String email
) {
}
