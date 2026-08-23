package com.example.skladdo.dto;

/**
 * Outcome of a test-send. {@code success} tells the settings page whether the SMTP config works;
 * {@code message} carries the SMTP error detail on failure so an admin can diagnose it.
 */
public record TestEmailResult(
        boolean success,
        String message
) {
}
