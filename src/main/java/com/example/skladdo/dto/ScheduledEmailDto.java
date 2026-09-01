package com.example.skladdo.dto;

import com.example.skladdo.model.ScheduledEmail;
import com.example.skladdo.model.ScheduledEmailAttachment;

import java.time.Instant;
import java.util.List;

/**
 * A queued send as the Scheduled tab shows it. Carries the recipient <em>count</em> rather than the
 * partners themselves - the list is about what is going out and when, and resolving every partner name
 * for every row would be a query per row for information nobody reads at that zoom level.
 *
 * <p>{@code failureReason} arrives already translated: the dispatcher stores a message key (it runs on a
 * background thread with no request locale to translate against), and it is resolved here, in the
 * reader's language.</p>
 */
public record ScheduledEmailDto(
        Long id,
        String recipientType,
        int recipientCount,
        Long templateId,
        String subject,
        Instant scheduledAt,
        String status,
        String failureReason,
        List<String> attachmentNames,
        Instant createdAt,
        Long createdById,
        Long serviceId
) {
    public static ScheduledEmailDto from(ScheduledEmail e, String translatedFailureReason) {
        return new ScheduledEmailDto(
                e.getId(),
                e.getRecipientType() != null ? e.getRecipientType().name() : null,
                e.getRecipientIds().size(),
                e.getTemplateId(),
                e.getSubject(),
                e.getScheduledAt(),
                e.getStatus() != null ? e.getStatus().name() : null,
                translatedFailureReason,
                e.getAttachments().stream().map(ScheduledEmailAttachment::getFileName).toList(),
                e.getCreatedAt(),
                e.getCreatedById(),
                e.getServiceId()
        );
    }
}
