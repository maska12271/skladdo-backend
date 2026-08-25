package com.example.skladdo.dto;

/**
 * Tells the reset page whether a token is still usable and, if so, which account it belongs to (so it
 * can greet the user). Never reveals anything for an invalid/expired/used token.
 */
public record PasswordResetInfoResponse(
        boolean valid,
        String email,
        /**
         * True when this link is a colleague's invitation rather than a password reset - the account has
         * never had a password of its own. One link type, two things to say: somebody being welcomed into
         * a company needs to be told where they have landed and by whom, which is not the same page as
         * "you asked to change your password".
         */
        boolean invitation,
        /** The company they are being invited into, so the welcome can name it. Null for a plain reset. */
        String companyName
) {
}
