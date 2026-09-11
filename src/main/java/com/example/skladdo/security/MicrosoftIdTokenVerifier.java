package com.example.skladdo.security;

import com.example.skladdo.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Checks that an ID token really was minted by Microsoft for this application, and reports who it is
 * about. The counterpart to {@link GoogleIdTokenVerifier} - same shape, same three things checked
 * (signature, audience, issuer) - but Microsoft's issuer is not a fixed string, which is the one place
 * this cannot simply copy Google's validator.
 *
 * <h2>Why the issuer check looks the way it does</h2>
 * <p>To accept both personal Microsoft accounts (outlook.com, live.com) and any organization's Entra
 * accounts through one button, sign-in goes through the {@code common} endpoint - and a token from
 * {@code common} is issued by whichever tenant the person actually signed into, so {@code iss} varies:
 * {@code https://login.microsoftonline.com/<tenant-id>/v2.0}. There is no single string to compare it
 * against. Microsoft's documented fix, applied here, is a consistency check instead of an equality one:
 * the tenant id inside {@code iss} must match the token's own {@code tid} claim. A personal account's
 * {@code tid} is the fixed GUID {@code 9188040d-6c67-4c5b-b112-36a304b66dad}; an organization's is that
 * tenant's id - either way, the check is the same.</p>
 *
 * <h2>Why {@code ExternalIdentity.emailVerified} is always {@code false} here</h2>
 * <p>Microsoft's ID token carries no {@code email_verified} claim at all, and Microsoft's own docs warn
 * that {@code email} "isn't guaranteed to be correct" and must never be used for authorization - unlike
 * Google, an Entra tenant can set a user's {@code mail} attribute to almost any string with no proof
 * anyone can receive mail there. Reporting {@code false} rather than guessing {@code true} is what makes
 * {@code MicrosoftAuthService} correctly refuse to do the one thing that claim would be dangerous for:
 * linking to an existing account purely because the email matches.</p>
 */
@Component
public class MicrosoftIdTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(MicrosoftIdTokenVerifier.class);

    /**
     * The tenant-independent JWKS: Microsoft signs with the same key set for every tenant, so this one
     * endpoint verifies a token from a personal account or from any organization alike.
     */
    private static final String JWKS_URI = "https://login.microsoftonline.com/common/discovery/v2.0/keys";

    private final String clientId;

    /**
     * Built on first use rather than at startup, because constructing it reaches out for Microsoft's keys -
     * work that must not sit between the application and being able to boot (or run its tests) offline.
     */
    private volatile JwtDecoder decoder;

    public MicrosoftIdTokenVerifier(@Value("${app.microsoft.client-id:}") String clientId) {
        this.clientId = clientId == null ? "" : clientId.trim();
    }

    /** Whether this deployment has a Microsoft client id, and so can offer Microsoft sign-in at all. */
    public boolean isConfigured() {
        return !clientId.isBlank();
    }

    /**
     * The verified identity behind an ID token. {@link ExternalIdentity#emailVerified()} is always
     * {@code false} - see the class documentation for why - so nothing calling this may use it to link to
     * an existing account by email; only {@link ExternalIdentity#subject()} may ever be matched on.
     *
     * @throws BadRequestException if Microsoft sign-in is not configured here, or the token is not a
     *                             valid, unexpired token issued by Microsoft for this application.
     */
    public ExternalIdentity verify(String idToken) {
        if (!isConfigured()) {
            throw new BadRequestException("error.auth.microsoft.notConfigured");
        }
        Jwt jwt;
        try {
            jwt = decoder().decode(idToken);
        } catch (JwtException e) {
            log.debug("Rejected a Microsoft ID token: {}", e.getMessage());
            throw new BadRequestException("error.auth.microsoft.invalidToken");
        }

        String subject = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        if (subject == null || subject.isBlank() || email == null || email.isBlank()) {
            // Present when the button asked for the openid+email scopes, which it does; missing either
            // means this is not the token we think it is.
            throw new BadRequestException("error.auth.microsoft.invalidToken");
        }
        return new ExternalIdentity(
                subject,
                email.trim().toLowerCase(Locale.ROOT),
                false,
                jwt.getClaimAsString("name"));
    }

    private JwtDecoder decoder() {
        JwtDecoder existing = decoder;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (decoder == null) {
                NimbusJwtDecoder built = NimbusJwtDecoder.withJwkSetUri(JWKS_URI).build();
                built.setJwtValidator(validator());
                decoder = built;
            }
            return decoder;
        }
    }

    /** Package-private as a test seam - see {@code GoogleIdTokenVerifier.validator} for why. */
    OAuth2TokenValidator<Jwt> validator() {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                new JwtClaimValidator<List<String>>(
                        JwtClaimNames.AUD, audience -> audience != null && audience.contains(clientId)),
                tenantConsistentIssuer());
    }

    /**
     * The multi-tenant issuer check described in the class documentation: {@code iss} must be
     * {@code https://login.microsoftonline.com/<tid>/v2.0} for the token's own {@code tid}. Not a
     * {@link JwtClaimValidator} because it has to read two claims together rather than judge one in
     * isolation.
     */
    private static OAuth2TokenValidator<Jwt> tenantConsistentIssuer() {
        return jwt -> {
            String tenantId = jwt.getClaimAsString("tid");
            String issuer = jwt.getClaimAsString(JwtClaimNames.ISS);
            String expected = tenantId == null ? null : "https://login.microsoftonline.com/" + tenantId + "/v2.0";
            if (tenantId == null || issuer == null || !issuer.equals(expected)) {
                return OAuth2TokenValidatorResult.failure(
                        new org.springframework.security.oauth2.core.OAuth2Error(
                                "invalid_issuer", "The issuer does not match the token's own tenant.", null));
            }
            return OAuth2TokenValidatorResult.success();
        };
    }
}
