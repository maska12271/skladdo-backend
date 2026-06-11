package com.example.tenderapp.dto;

import com.example.tenderapp.model.Role;
import jakarta.validation.constraints.NotNull;

/**
 * Update an existing user. {@code password} is optional - when blank/null the password is left
 * unchanged.
 */
public record UpdateUserRequest(
        String fullName,
        @NotNull Role role,
        String password
) {
}
