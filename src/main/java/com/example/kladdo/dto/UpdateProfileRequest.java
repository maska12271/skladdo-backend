package com.example.kladdo.dto;

import jakarta.validation.constraints.Size;

/**
 * The signed-in user's own editable profile fields. Both are optional wrapper types: an omitted field is
 * left unchanged rather than cleared, so the account page can save one section at a time.
 */
public record UpdateProfileRequest(
        @Size(max = 255) String fullName,
        /** Interface language ("en"/"et"/"ru"); ignored when it is not one the app ships. */
        @Size(max = 5) String language
) {
}
