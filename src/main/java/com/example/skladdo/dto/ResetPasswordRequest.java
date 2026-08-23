package com.example.skladdo.dto;

import com.example.skladdo.security.PasswordPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank String token,
        @NotBlank @Size(min = PasswordPolicy.MIN_LENGTH) String password
) {
}
