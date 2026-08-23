package com.example.skladdo.dto;

import com.example.skladdo.model.EmailTemplate;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * Read/write view of an {@link EmailTemplate}. {@code id} is null on create; audit fields are read-only
 * (ignored on write).
 *
 * <p>{@code active} is a {@link Boolean}, not a primitive, so omitting it means "leave it at the default"
 * rather than failing the whole request. As a primitive it had no value to bind to when absent, and a
 * perfectly valid body without it was rejected as unreadable (finding N-007).</p>
 */
public record EmailTemplateDto(
        Long id,
        @NotBlank String name,
        @NotBlank String subject,
        @NotBlank String body,
        Boolean active,
        Instant createdAt,
        Instant updatedAt
) {
    /** Defaults to active, matching what {@link #from} reports for a template with no flag set. */
    public boolean activeOrDefault() {
        return active == null || active;
    }

    public static EmailTemplateDto from(EmailTemplate t) {
        return new EmailTemplateDto(
                t.getId(),
                t.getName(),
                t.getSubject(),
                t.getBody(),
                t.getActive() == null || t.getActive(),
                t.getCreatedAt(),
                t.getUpdatedAt()
        );
    }
}
