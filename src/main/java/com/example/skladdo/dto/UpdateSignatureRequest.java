package com.example.skladdo.dto;

import jakarta.validation.constraints.Size;

/** The signed-in user's own HTML email signature (may be blank/null to clear it). */
public record UpdateSignatureRequest(
        @Size(max = 5000) String signature
) {
}
