package com.example.kladdo.dto;

import com.example.kladdo.model.Role;
import jakarta.validation.constraints.NotNull;

/**
 * Update an existing user. {@code password} is optional - when blank/null the password is left
 * unchanged.
 */
public record UpdateUserRequest(
        String fullName,
        @NotNull Role role,
        String password,
        Boolean canSeePrices
) {
}
