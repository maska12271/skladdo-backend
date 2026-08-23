package com.example.skladdo.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * Issues and validates stateless JWTs. Tokens carry the user id, company id, role and full name so
 * the API does not need a session store.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-ms}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    /** Mints a token for a normal session, working inside the account's own company. */
    public String generateToken(CustomUserDetails user) {
        return generateToken(user, user.getHomeCompanyId());
    }

    /**
     * Mints a token pinned to {@code activeCompanyId} - the tenant the session works in. For everyone but
     * a switched warehouse operator this is the account's own company.
     *
     * <p>The claim is only ever a <em>request</em> to act in that company: {@link JwtAuthenticationFilter}
     * re-checks it against a live connection on every request, so a tampered or stale token grants
     * nothing.</p>
     */
    public String generateToken(CustomUserDetails user, Long activeCompanyId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);
        return Jwts.builder()
                .subject(user.getEmail())
                .claims(Map.of(
                        "userId", user.getId(),
                        "companyId", user.getHomeCompanyId(),
                        "activeCompanyId", activeCompanyId == null ? user.getHomeCompanyId() : activeCompanyId,
                        "role", user.getRole().name(),
                        "fullName", user.getFullName() == null ? "" : user.getFullName()
                ))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    /** The company the token asks to act in, or null when it predates the claim. */
    public Long extractActiveCompanyId(Claims claims) {
        Object value = claims.get("activeCompanyId");
        return value instanceof Number number ? number.longValue() : null;
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractUsername(String token) {
        return parseClaims(token).getSubject();
    }
}
