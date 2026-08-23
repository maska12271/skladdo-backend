package com.example.skladdo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Request to send one email per manufacturer. {@link #subject}/{@link #body} are the (possibly
 * template-derived, possibly edited) raw text with {@code {{token}}} placeholders that are rendered
 * per recipient. {@link #templateId} is recorded on each sent email as the source reference, or null
 * when composed ad hoc.
 */
public record SendEmailRequest(
        @NotEmpty List<Long> manufacturerIds,
        Long templateId,
        @NotBlank String subject,
        @NotBlank String body
) {
}
