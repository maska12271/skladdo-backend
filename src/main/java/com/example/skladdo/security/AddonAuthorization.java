package com.example.skladdo.security;

import com.example.skladdo.model.AddonType;
import com.example.skladdo.service.PlanService;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * Path-level gate for the purchasable add-ons: a company that does not pay for tenders or manufacturer
 * emails cannot reach those endpoints at all, not even by typing the URL.
 *
 * <p>Expressed in {@link com.example.skladdo.config.SecurityConfig} against whole path prefixes rather
 * than as a {@code @PreAuthorize} on each handler. A method-level annotation <em>replaces</em> a
 * class-level one instead of adding to it, so guarding a controller that way means annotating every
 * method and remembering to annotate the next one somebody adds - and a single miss is an ungated
 * endpoint. One prefix covers the area for good.</p>
 *
 * <p>This is the company-level entitlement only. The per-user {@code PermissionModule} check stays on the
 * handlers where it already is; both have to pass.</p>
 */
@Component
public class AddonAuthorization {

    private final PlanService planService;

    public AddonAuthorization(PlanService planService) {
        this.planService = planService;
    }

    /**
     * Allows the request only while the calling company has {@code addon} active.
     *
     * <p>Safe to consult here: authorization runs after {@link JwtAuthenticationFilter}, so the tenant is
     * already bound and the lookup resolves against the right company.</p>
     */
    public AuthorizationManager<RequestAuthorizationContext> requires(AddonType addon) {
        return (authentication, context) -> {
            // No session means no tenant is bound, so the lookup would query nothing at best. Denying here
            // still surfaces as a 401: Spring routes an anonymous denial to the authentication entry point.
            Authentication auth = authentication.get();
            if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
                return new AuthorizationDecision(false);
            }
            return new AuthorizationDecision(planService.hasAddon(addon));
        };
    }
}
