package com.example.kladdo.dto;

import com.example.kladdo.model.SentEmail;

import java.time.Instant;

/**
 * List-row view of a {@link SentEmail}: enough to render the sent-emails table without the (large)
 * body snapshot. {@code viewed}/{@code replied} are convenience booleans alongside the timestamps.
 */
public record SentEmailDto(
        Long id,
        Long manufacturerId,
        String manufacturerName,
        String recipientEmail,
        String subject,
        String status,
        Instant sentAt,
        Long sentById,
        boolean viewed,
        Instant viewedAt,
        boolean replied,
        Instant repliedAt
) {
    public static SentEmailDto from(SentEmail e) {
        return new SentEmailDto(
                e.getId(),
                e.getManufacturer() != null ? e.getManufacturer().getId() : null,
                e.getManufacturerNameSnapshot(),
                e.getRecipientEmail(),
                e.getSubjectSnapshot(),
                e.getStatus() != null ? e.getStatus().name() : null,
                e.getSentAt(),
                e.getSentById(),
                e.getViewedAt() != null,
                e.getViewedAt(),
                e.getRepliedAt() != null,
                e.getRepliedAt()
        );
    }
}
