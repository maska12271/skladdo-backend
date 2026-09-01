package com.example.skladdo.dto;

import com.example.skladdo.model.EmailRecipientType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * Request to send one email per recipient. {@link #subject}/{@link #body} are the (possibly
 * template-derived, possibly edited) raw text with {@code {{token}}} placeholders that are rendered
 * per recipient. {@link #templateId} is recorded on each sent email as the source reference, or null
 * when composed ad hoc. {@link #contactId} narrows a single-recipient send to a named person.
 *
 * <p>{@link #recipientType} says which side of the address book {@link #recipientIds} refer to. A send is
 * one type at a time: the compose form opens from the clients list or the manufacturers list, and a
 * contact belongs to one partner, so a mixed batch would have no answer to "which contact".</p>
 */
public record SendEmailRequest(
        @NotNull EmailRecipientType recipientType,
        @NotEmpty List<Long> recipientIds,
        Long templateId,
        @NotBlank String subject,
        @NotBlank String body,
        /**
         * A named person at the partner to write to instead of the partner's own address.
         *
         * <p>Only meaningful for a send to a single partner - a contact belongs to one, so there is no
         * answer to "which contact" across a bulk selection. Ignored when several are selected, and
         * ignored again if the id turns out not to name anyone at that partner.</p>
         */
        Long contactId,

        /**
         * When to send. Null (the common case) means now; a future instant queues the send instead and
         * the response carries a {@code scheduledId} rather than per-recipient results.
         */
        Instant scheduledAt,

        /**
         * The {@code Service} this send is a reminder about, when composed from the recurring-service
         * flow. Null for an ordinary compose. Only meaningful alongside {@link #scheduledAt} - see
         * {@code ScheduledEmail.serviceId}.
         */
        Long serviceId
) {
}
