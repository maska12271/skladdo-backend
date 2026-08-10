package com.example.kladdo.dto;

import java.time.Instant;

/**
 * Outcome of issuing a password setup/reset link for a user. {@code setupLink} is always returned so
 * an admin can copy and share it manually when {@code emailSent} is {@code false} (e.g. the company's
 * SMTP isn't configured yet or the send failed).
 */
public record SetupLinkResponse(
        boolean emailSent,
        String setupLink,
        Instant expiresAt
) {
}
