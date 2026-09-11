package com.example.skladdo.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Sign-in through Microsoft. The whole payload is the ID token MSAL handed the browser - see
 * {@link GoogleLoginRequest}, whose reasoning is identical here.
 */
public record MicrosoftLoginRequest(@NotBlank String idToken) {
}
