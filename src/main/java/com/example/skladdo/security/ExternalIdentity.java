package com.example.skladdo.security;

/**
 * Who an identity provider says the person signing in is, once their token has been verified. Provider
 * neutral on purpose: whatever a second provider's token looks like, it reduces to these four facts, so
 * everything downstream of verification stays written once.
 *
 * @param subject       the provider's permanent id for this person (the OIDC {@code sub} claim). Stored as
 *                      {@code User.externalAuthId} and matched on before the email.
 * @param email         the address the provider holds for them, lower-cased.
 * @param emailVerified whether the provider has actually confirmed that address. An unverified one may
 *                      never be used to find an existing account - anyone can put someone else's address
 *                      on an account they own, so trusting it would hand them that person's login.
 * @param fullName      display name, or {@code null} if the provider did not supply one.
 */
public record ExternalIdentity(String subject, String email, boolean emailVerified, String fullName) {

    /**
     * A name to put on the account. Google sends one for every personal account, but a Workspace account
     * can have it withheld by policy, and no form asks for it when the provider is supposed to supply it -
     * so fall back to the local part of the address rather than creating an account with no name at all.
     */
    public String displayName() {
        if (fullName != null && !fullName.isBlank()) {
            return fullName.trim();
        }
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}
