package com.example.kladdo.security;

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

    public static Long currentCompanyId() {
        return currentUser().getCompanyId();
    }

    public static Long currentUserId() {
        return currentUser().getId();
    }
}
