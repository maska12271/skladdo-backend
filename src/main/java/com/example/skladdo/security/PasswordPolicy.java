package com.example.skladdo.security;

/**
 * The one place the password rules live.
 *
 * <p>The minimum is enforced at three independent points - signup ({@code RegisterRequest}), the
 * reset/setup link ({@code ResetPasswordRequest}) and a signed-in user changing their own password
 * ({@code AuthService.changePassword}). They are separate code paths, so a literal in each is a standing
 * invitation for them to drift apart and leave one route weaker than the others. Anything that changes the
 * policy changes it here.</p>
 *
 * <p>The user-facing message lives in the message bundles ({@code Size.password},
 * {@code error.user.passwordTooShort}) and repeats the number in prose, so it has to be updated alongside
 * this constant.</p>
 */
public final class PasswordPolicy {

    /**
     * Minimum password length. Raised from 6 to 8 on 2026-08-10 (finding N-002): six characters is below
     * any current baseline for a product holding order, pricing and invoice data.
     *
     * <p>Only checked when a password is <em>set</em>, never at login, so raising it does not lock out
     * accounts whose existing password is shorter.</p>
     */
    public static final int MIN_LENGTH = 8;

    private PasswordPolicy() {
    }
}
