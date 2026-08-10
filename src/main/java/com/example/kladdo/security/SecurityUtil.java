package com.example.kladdo.security;

import com.example.kladdo.model.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Convenience accessors for the currently authenticated {@link CustomUserDetails}.
 */
public final class SecurityUtil {

    private SecurityUtil() {
    }

    public static CustomUserDetails currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CustomUserDetails details)) {
            throw new IllegalStateException("No authenticated user in the security context");
        }
        return details;
    }

    /**
     * The company the current request works in. This is what every data-scoped lookup should use: for a
     * switched warehouse operator it is the client company, which is precisely what makes one login work
     * across several clients.
     */
    public static Long currentCompanyId() {
        return currentUser().getCompanyId();
    }

    /** The company that owns the login. Differs from {@link #currentCompanyId()} only in a partner session. */
    public static Long currentHomeCompanyId() {
        return currentUser().getHomeCompanyId();
    }

    /**
     * True when the caller is a warehouse operator's user working inside a client company rather than
     * their own. Such a session is capped at warehouse-staff access however senior the account is at home.
     */
    public static boolean isPartnerSession() {
        return currentUser().isPartnerSession();
    }

    public static Long currentUserId() {
        return currentUser().getId();
    }

    /**
     * True when the caller is an owner or administrator - the roles that bypass fine-grained permissions
     * and see every user's data company-wide (used to scope "own vs. everyone's" views).
     */
    public static boolean currentUserIsManager() {
        Role role = currentUser().getRole();
        return role == Role.OWNER || role == Role.ADMINISTRATOR;
    }
}
