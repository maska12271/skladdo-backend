package com.example.skladdo.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Request to send a test email to {@code recipient} using the company's saved SMTP settings. */
public record TestEmailRequest(
        @NotBlank @Email String recipient
) {
}
