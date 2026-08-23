package com.example.skladdo.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The signed-in user changing their own password. Both fields are wrapper types (String) so an omitted
 * field deserializes to null rather than failing under Jackson 3; presence is enforced by {@code @NotBlank}
 * and the current-password check happens in the service.
 */
public record ChangePasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank String newPassword
) {
}
