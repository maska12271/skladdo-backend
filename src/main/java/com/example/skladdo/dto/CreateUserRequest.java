package com.example.skladdo.dto;

import com.example.skladdo.model.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Create a new user. No password is set here: the account is created "awaiting password setup" and the
 * user sets their own password through an emailed link (see {@code PasswordResetService}).
 */
public record CreateUserRequest(
        @NotBlank @Email String email,
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
