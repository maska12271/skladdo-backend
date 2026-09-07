package com.example.skladdo.security;

import com.example.skladdo.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
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
import java.util.Set;

/**
 * Checks that an ID token really was minted by Google for this application, and reports who it is about.
 *
 * <p>The button on the sign-in page talks to Google directly and comes back with a signed ID token; this
 * class is the only thing standing between that token and a session, so every claim that makes it
 * trustworthy is checked here:</p>
 * <ul>
 *   <li><b>signature</b> - against Google's published keys, fetched and cached by {@link NimbusJwtDecoder}
 *       (which also re-fetches when Google rotates them, so there is nothing to schedule);</li>
 *   <li><b>expiry</b> - via the decoder's default timestamp validator;</li>
 *   <li><b>audience</b> - the token must name <em>our</em> client id. Without this check a token minted for
 *       any other Google application would be accepted here, and every one of those is issued to somebody
 *       else's site;</li>
 *   <li><b>issuer</b> - Google spells this two ways and has done for years, so both are allowed.</li>
 * </ul>
 *
 * <p>Deliberately not Google's {@code google-api-client}: it would pull Jackson 2 (plus Guava and Apache
 * HttpClient) onto a Spring Boot 4 classpath that has moved to Jackson 3, to do what the decoder already on
 * hand does in a dozen lines - and the same decoder works unchanged against any other OIDC provider's JWKS.
 *
 * <p>Unconfigured is a supported state, not a startup failure: a deployment without
 * {@code app.google.client-id} simply has no Google sign-in, and the endpoints say so. Otherwise every
 * developer would need a Google Cloud project before the application would boot.</p>
 */
@Component
public class GoogleIdTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleIdTokenVerifier.class);

    private static final String JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";

    /** Google mints tokens under both spellings; which one you get is not something to rely on. */
    private static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

    private final String clientId;

    /**
     * Built on first use rather than at startup, because constructing it reaches out for Google's keys -
     * work that must not sit between the application and being able to boot (or run its tests) offline.
     */
    private volatile JwtDecoder decoder;

    public GoogleIdTokenVerifier(@Value("${app.google.client-id:}") String clientId) {
        this.clientId = clientId == null ? "" : clientId.trim();
    }

    /** Whether this deployment has a Google client id, and so can offer Google sign-in at all. */
    public boolean isConfigured() {
        return !clientId.isBlank();
    }

    /**
     * The verified identity behind an ID token.
     *
     * @throws BadRequestException if Google sign-in is not configured here, or the token is not a valid,
     *                             unexpired token issued by Google for this application. The failure is
     *                             deliberately one message: which check failed is useful to an attacker
     *                             and not to the person, who can only ever try again.
     */
    public ExternalIdentity verify(String idToken) {
        if (!isConfigured()) {
            throw new BadRequestException("error.auth.google.notConfigured");
        }
        Jwt jwt;
        try {
            jwt = decoder().decode(idToken);
        } catch (JwtException e) {
            log.debug("Rejected a Google ID token: {}", e.getMessage());
            throw new BadRequestException("error.auth.google.invalidToken");
        }

        String subject = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        if (subject == null || subject.isBlank() || email == null || email.isBlank()) {
            // A Google token always carries both when the request asked for the openid+email scopes, which
            // the sign-in button does. Missing either means this is not the token we think it is.
            throw new BadRequestException("error.auth.google.invalidToken");
        }
        Boolean verified = jwt.getClaim("email_verified");
        return new ExternalIdentity(
                subject,
                email.trim().toLowerCase(Locale.ROOT),
                Boolean.TRUE.equals(verified),
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

    /**
     * Package-private as a test seam. The audience rule below cannot be reached from outside: it runs only
     * after signature verification, and producing a token that passes that would mean holding Google's
     * private key. Testing it therefore means calling this directly - see {@code GoogleIdTokenVerifierTest}.
     */
    OAuth2TokenValidator<Jwt> validator() {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                new JwtClaimValidator<List<String>>(
                        JwtClaimNames.AUD, audience -> audience != null && audience.contains(clientId)),
                new JwtClaimValidator<String>(
                        JwtClaimNames.ISS, issuer -> issuer != null && ISSUERS.contains(issuer)));
    }
}
