package com.example.skladdo.dto;

import java.time.Instant;

/**
 * Returned when an admin creates a user. Besides the new account, it carries the outcome of the
 * automatically-issued password setup invitation so the UI can either confirm the email was sent or
 * offer the copyable {@code setupLink} as a fallback.
 */
public record CreatedUserResponse(
        UserDto user,
        boolean emailSent,
        String setupLink,
        Instant expiresAt
) {
}
