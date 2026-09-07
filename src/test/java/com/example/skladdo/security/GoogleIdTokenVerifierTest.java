package com.example.skladdo.security;

import com.example.skladdo.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The claim rules that decide whether a Google ID token is one of <em>ours</em>.
 *
 * <p>These cannot be reached through {@code verify()}: they run only after the signature has been checked
 * against Google's keys, and getting a token past that would mean holding Google's private key. So the
 * validator is exercised directly — which matters most for the audience rule, the one that stands between
 * this application and every ID token Google issues to <em>somebody else's</em> site. Without it, anyone
 * running any Google-signed app could take a token their own users handed them and sign in here as those
 * people.</p>
 */
class GoogleIdTokenVerifierTest {

    private static final String CLIENT_ID = "our-client.apps.googleusercontent.com";

    private final GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier(CLIENT_ID);
    private final OAuth2TokenValidator<Jwt> validator = verifier.validator();

    /** A token as Google mints them, with whatever claims the case under test wants to vary. */
    private static Jwt token(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("ignored")
                .header("alg", "RS256")
                .issuedAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .subject("108372918273645500001");
        claims.forEach(builder::claim);
        return builder.build();
    }

    private static Jwt validToken() {
        return token(Map.of("iss", "https://accounts.google.com", "aud", List.of(CLIENT_ID)));
    }

    private boolean rejects(Jwt jwt) {
        return validator.validate(jwt).hasErrors();
    }

    @Test
    void acceptsATokenMintedForThisClient() {
        assertFalse(rejects(validToken()));
    }

    @Test
    void rejectsATokenMintedForADifferentClient() {
        // The whole point of the audience check. This token is perfectly valid and genuinely signed by
        // Google - it just was not issued to us.
        assertTrue(rejects(token(Map.of(
                "iss", "https://accounts.google.com",
                "aud", List.of("somebody-elses-app.apps.googleusercontent.com")))));
    }

    @Test
    void acceptsATokenListingThisClientAmongSeveralAudiences() {
        assertFalse(rejects(token(Map.of(
                "iss", "https://accounts.google.com",
                "aud", List.of("another-client.apps.googleusercontent.com", CLIENT_ID)))));
    }

    @Test
    void acceptsBothSpellingsOfGoogleAsIssuer() {
        // Google mints tokens under both, and which one arrives is not something to rely on.
        assertFalse(rejects(token(Map.of("iss", "https://accounts.google.com", "aud", List.of(CLIENT_ID)))));
        assertFalse(rejects(token(Map.of("iss", "accounts.google.com", "aud", List.of(CLIENT_ID)))));
    }

    @Test
    void rejectsATokenFromAnotherIssuer() {
        assertTrue(rejects(token(Map.of(
                "iss", "https://accounts.evil.example",
                "aud", List.of(CLIENT_ID)))));
    }

    @Test
    void rejectsAnExpiredToken() {
        Jwt expired = Jwt.withTokenValue("ignored")
                .header("alg", "RS256")
                .issuedAt(Instant.now().minus(3, ChronoUnit.HOURS))
                .expiresAt(Instant.now().minus(2, ChronoUnit.HOURS))
                .subject("108372918273645500001")
                .claim("iss", "https://accounts.google.com")
                .claim("aud", List.of(CLIENT_ID))
                .build();
        assertTrue(rejects(expired));
    }

    @Test
    void rejectsATokenCarryingNoAudienceAtAll() {
        assertTrue(rejects(token(Map.of("iss", "https://accounts.google.com"))));
    }

    // --- Not configured --------------------------------------------------------------------------

    @Test
    void reportsItselfUnconfiguredWithoutAClientId() {
        // A deployment with no Google project must still start and still serve password sign-in, so this
        // is a supported state rather than a startup failure - it just cannot verify anything.
        GoogleIdTokenVerifier unconfigured = new GoogleIdTokenVerifier("  ");

        assertFalse(unconfigured.isConfigured());
        assertEquals("error.auth.google.notConfigured",
                assertThrows(BadRequestException.class, () -> unconfigured.verify("any.token.here"))
                        .getMessageKey());
    }

    @Test
    void treatsAMissingClientIdPropertyAsUnconfigured() {
        assertFalse(new GoogleIdTokenVerifier(null).isConfigured());
    }
}
