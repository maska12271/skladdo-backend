package com.example.skladdo.dto;

import com.example.skladdo.model.Role;
import jakarta.validation.constraints.NotNull;

/**
 * Update an existing user. Passwords are never set here - a user always sets their own password via an
 * emailed link (admins can trigger one with the "send reset link" action).
 */
public record UpdateUserRequest(
        String fullName,
        @NotNull Role role,
        Boolean canSeePrices,
        /**
         * Optional avatar, set by the administrator on the user's behalf: either an uploaded picture's
         * storage key, or a preset icon + colour. The user can change it themselves afterwards
         * ({@code PUT /api/auth/me/avatar}); this only saves them from starting with a blank one.
         */
        String avatarKey,
        String avatarIcon,
        String avatarColor
) {
}
