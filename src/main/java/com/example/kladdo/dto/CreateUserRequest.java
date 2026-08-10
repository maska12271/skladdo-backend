package com.example.kladdo.dto;

import com.example.kladdo.model.Role;
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
        Boolean canSeePrices
) {
}
