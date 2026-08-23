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
        Boolean canSeePrices
) {
}
