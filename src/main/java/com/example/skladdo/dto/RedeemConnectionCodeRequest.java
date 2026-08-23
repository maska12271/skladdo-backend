package com.example.skladdo.dto;

import jakarta.validation.constraints.NotBlank;

/** The code a warehouse account types to start working for the business company that issued it. */
public record RedeemConnectionCodeRequest(
        @NotBlank String code
) {
}
