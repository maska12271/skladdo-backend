package com.example.skladdo.security;

import com.example.skladdo.model.ConnectionStatus;
import com.example.skladdo.model.WarehouseConnection;
import com.example.skladdo.repository.WarehouseConnectionRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Validates the {@code Authorization: Bearer <token>} header on each request. On success it
 * populates the Spring Security context and binds the request's company to {@link TenantContext}
 * so Hibernate scopes all data access to that company. The tenant binding is always cleared at
 * the end of the request to avoid leaking across pooled threads.
 *
 * <p>Normally that company is the account's own. A warehouse operator's staff can instead switch into a
 * client company they are connected to, which is carried in the token's {@code activeCompanyId} claim.
 * <strong>The claim is never trusted on its own</strong> - it is re-checked here against a live
 * {@link ConnectionStatus#ACTIVE} {@link WarehouseConnection} on every single request, so revoking a
 * connection cuts access immediately rather than whenever the operator's token happens to expire. A claim
 * that no longer checks out silently falls back to the user's own company.</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;
    private final WarehouseConnectionRepository connectionRepository;

    public JwtAuthenticationFilter(JwtService jwtService,
                                   CustomUserDetailsService userDetailsService,
                                   WarehouseConnectionRepository connectionRepository) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.connectionRepository = connectionRepository;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        try {
            if (header != null && header.startsWith("Bearer ")
                    && SecurityContextHolder.getContext().getAuthentication() == null) {
                String token = header.substring(7);
                try {
                    Claims claims = jwtService.parseClaims(token);
                    CustomUserDetails userDetails =
                            (CustomUserDetails) userDetailsService.loadUserByUsername(claims.getSubject());

                    if (userDetails.isEnabled()) {
                        userDetails = applyActiveCompany(userDetails, jwtService.extractActiveCompanyId(claims));

                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(
                                        userDetails, null, userDetails.getAuthorities());
                        authentication.setDetails(
                                new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                        TenantContext.setCompanyId(userDetails.getCompanyId());
                    }
                } catch (Exception ex) {
                    // Invalid / expired token - leave the context unauthenticated.
                    SecurityContextHolder.clearContext();
                }
            }

            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Resolves the company this request may work in. Returns the principal unchanged for an ordinary
     * session; for a switched one it grants the client company only after confirming a live connection,
     * carrying that connection's price visibility onto the principal.
     */
    private CustomUserDetails applyActiveCompany(CustomUserDetails userDetails, Long requestedCompanyId) {
        if (requestedCompanyId == null || requestedCompanyId.equals(userDetails.getHomeCompanyId())) {
            return userDetails;
        }
        // Connection revoked (or never existed): fall back to the user's own company rather than failing
        // the request, so a stale tab lands back on their own data instead of erroring out.
        return connectionRepository
                .findFirstByWarehouseCompanyIdAndClientCompanyIdAndStatus(
                        userDetails.getHomeCompanyId(), requestedCompanyId, ConnectionStatus.ACTIVE)
                .map(live -> userDetails.actingIn(requestedCompanyId, live.isCanSeePrices()))
                .orElse(userDetails);
    }
}
