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
 * The claim rules that decide whether a Microsoft ID token is one of <em>ours</em> - see
 * {@code GoogleIdTokenVerifierTest} for why these are exercised directly rather than through
 * {@code verify()}. The one novel thing here, absent from Google's version, is the multi-tenant issuer
 * check: unlike Google, Microsoft's {@code iss} varies per tenant, so there is no fixed string to compare
 * it against - the class documentation on {@link MicrosoftIdTokenVerifier} explains the consistency check
 * used instead, and this is what pins it down.
 */
class MicrosoftIdTokenVerifierTest {

    private static final String CLIENT_ID = "our-client-id";

    /** A work/school tenant's id, chosen arbitrarily - any GUID-shaped string does for these tests. */
    private static final String WORK_TENANT_ID = "72f988bf-86f1-41af-91ab-2d7cd011db47";

    /** The fixed tenant id every personal Microsoft account signs in under, per Microsoft's own docs. */
    private static final String CONSUMERS_TENANT_ID = "9188040d-6c67-4c5b-b112-36a304b66dad";

    private final MicrosoftIdTokenVerifier verifier = new MicrosoftIdTokenVerifier(CLIENT_ID);
    private final OAuth2TokenValidator<Jwt> validator = verifier.validator();

    private static Jwt token(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("ignored")
                .header("alg", "RS256")
                .issuedAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .subject("AAAAAAAAAAAAAAAAAAAAAImZgz1jI4qJEeqO0xh8FQU");
        claims.forEach(builder::claim);
        return builder.build();
    }

    /** A token whose {@code iss} names the same tenant as its own {@code tid} - the only shape Microsoft
     * itself ever issues, and so the only shape this validator should accept. */
    private static Jwt consistentToken(String tenantId) {
        return token(Map.of(
                "aud", List.of(CLIENT_ID),
                "tid", tenantId,
                "iss", "https://login.microsoftonline.com/" + tenantId + "/v2.0"));
    }

    private boolean rejects(Jwt jwt) {
        return validator.validate(jwt).hasErrors();
    }

    @Test
    void acceptsAWorkOrSchoolTenantWhoseIssuerNamesItsOwnTenant() {
        assertFalse(rejects(consistentToken(WORK_TENANT_ID)));
    }

    @Test
    void acceptsAPersonalMicrosoftAccountUnderTheConsumersTenant() {
        // Personal accounts (outlook.com, live.com) are not a special case in the code - they are simply
        // consistent under a specific, fixed tenant id, exactly like any organization's.
        assertFalse(rejects(consistentToken(CONSUMERS_TENANT_ID)));
    }

    @Test
    void rejectsAnIssuerNamingADifferentTenantThanTheTokensOwnTid() {
        // The actual vulnerability this check exists to close: a token whose iss claims one tenant while
        // tid claims another is not a shape Microsoft would ever issue honestly.
        assertTrue(rejects(token(Map.of(
                "aud", List.of(CLIENT_ID),
                "tid", WORK_TENANT_ID,
                "iss", "https://login.microsoftonline.com/" + CONSUMERS_TENANT_ID + "/v2.0"))));
    }

    @Test
    void rejectsATokenWithNoTidClaimAtAll() {
        assertTrue(rejects(token(Map.of(
                "aud", List.of(CLIENT_ID),
                "iss", "https://login.microsoftonline.com/" + WORK_TENANT_ID + "/v2.0"))));
    }

    @Test
    void rejectsATokenMintedForADifferentClient() {
        assertTrue(rejects(token(Map.of(
                "aud", List.of("somebody-elses-app"),
                "tid", WORK_TENANT_ID,
                "iss", "https://login.microsoftonline.com/" + WORK_TENANT_ID + "/v2.0"))));
    }

    @Test
    void rejectsAnExpiredToken() {
        Jwt expired = Jwt.withTokenValue("ignored")
                .header("alg", "RS256")
                .issuedAt(Instant.now().minus(3, ChronoUnit.HOURS))
                .expiresAt(Instant.now().minus(2, ChronoUnit.HOURS))
                .subject("some-subject")
                .claim("aud", List.of(CLIENT_ID))
                .claim("tid", WORK_TENANT_ID)
                .claim("iss", "https://login.microsoftonline.com/" + WORK_TENANT_ID + "/v2.0")
                .build();
        assertTrue(rejects(expired));
    }

    @Test
    void reportsItselfUnconfiguredWithoutAClientId() {
        MicrosoftIdTokenVerifier unconfigured = new MicrosoftIdTokenVerifier("  ");

        assertFalse(unconfigured.isConfigured());
        assertEquals("error.auth.microsoft.notConfigured",
                assertThrows(BadRequestException.class, () -> unconfigured.verify("any.token.here"))
                        .getMessageKey());
    }

    @Test
    void treatsAMissingClientIdPropertyAsUnconfigured() {
        assertFalse(new MicrosoftIdTokenVerifier(null).isConfigured());
    }
}
