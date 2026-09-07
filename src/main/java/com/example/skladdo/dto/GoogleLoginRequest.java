package com.example.skladdo.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Sign-in through Google. The whole payload is the ID token the Google button handed the browser: the
 * email and the person's name are read from it after it has been verified, never from the request, or
 * anyone could sign in as anyone by typing a different address.
 */
public record GoogleLoginRequest(@NotBlank String idToken) {
}
