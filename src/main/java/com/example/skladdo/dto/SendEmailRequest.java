package com.example.skladdo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Request to send one email per manufacturer. {@link #subject}/{@link #body} are the (possibly
 * template-derived, possibly edited) raw text with {@code {{token}}} placeholders that are rendered
 * per recipient. {@link #templateId} is recorded on each sent email as the source reference, or null
 * when composed ad hoc. {@link #contactId} narrows a single-recipient send to a named person.
 */
public record SendEmailRequest(
        @NotEmpty List<Long> manufacturerIds,
        Long templateId,
        @NotBlank String subject,
        @NotBlank String body,
        /**
         * A named person at the manufacturer to write to instead of the company's own address.
         *
         * <p>Only meaningful for a send to a single manufacturer - a contact belongs to one, so there is
         * no answer to "which contact" across a bulk selection. Ignored when several are selected, and
         * ignored again if the id turns out not to name anyone at that manufacturer.</p>
         */
        Long contactId
) {
}
