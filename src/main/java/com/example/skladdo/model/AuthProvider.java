package com.example.skladdo.model;

/**
 * Which credential an account signs in with.
 *
 * <p>This is about the <em>credential</em>, not about which buttons the person may press: an account is
 * {@link #LOCAL} exactly when it has a password of its own. Linking Google to an existing password account
 * does not change it to {@link #GOOGLE} - that account keeps its password and gains a second way in, which
 * is why the linked identity lives in its own column ({@code User.externalAuthId}) rather than being
 * inferred from this one.</p>
 *
 * <p>Adding a provider here means adding a {@code widenEnumCheck} line to {@code SchemaMigrations} as
 * well: Hibernate renders this column as a CHECK constraint listing today's values, and an existing
 * database keeps the old list until it is rewritten - see {@code SchemaMigrations.widenEnumCheck}.</p>
 */
public enum AuthProvider {

    /** Signs in with a password stored here. Every account predating external sign-in is this. */
    LOCAL,

    /**
     * Signs in through Google and has no usable password - its {@code passwordHash} is a random
     * placeholder, the same trick an invited-but-not-yet-activated account uses. Setting a password later
     * (through the ordinary reset link) turns the account back into a {@link #LOCAL} one.
     */
    GOOGLE
}
