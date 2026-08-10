package com.example.kladdo.dto;

import com.example.kladdo.model.SentEmail;

import java.time.Instant;
import java.util.List;

/**
 * Full view of a bulk send: the shared subject/sender/time and aggregate counts, plus one
 * {@link RecipientDto} per recipient showing that recipient's individual delivery, open and reply state.
 * Powers the batch detail page ("who was it sent to, who opened it, who replied"). Each recipient's id
 * opens the existing single-email detail (rendered body + reply thread).
 */
public record SentEmailBatchDetailDto(
        String batchId,
        String subject,
        Long sentById,
        Instant sentAt,
        int recipientCount,
        int viewedCount,
        int repliedCount,
        int sentCount,
        int failedCount,
        List<RecipientDto> recipients
) {
    public record RecipientDto(
            Long id,
            Long manufacturerId,
            String manufacturerName,
            String recipientEmail,
            String status,
            boolean viewed,
            Instant viewedAt,
            boolean replied,
            Instant repliedAt
    ) {
        public static RecipientDto from(SentEmail e) {
            return new RecipientDto(
                    e.getId(),
                    e.getManufacturer() != null ? e.getManufacturer().getId() : null,
                    e.getManufacturerNameSnapshot(),
                    e.getRecipientEmail(),
                    e.getStatus() != null ? e.getStatus().name() : null,
                    e.getViewedAt() != null,
                    e.getViewedAt(),
                    e.getRepliedAt() != null,
                    e.getRepliedAt()
            );
        }
    }

    /** Builds the batch view from its recipient rows (all sharing one {@code batchId}), computing counts. */
    public static SentEmailBatchDetailDto from(String batchId, List<SentEmail> rows) {
        int viewed = 0, replied = 0, sent = 0, failed = 0;
        List<RecipientDto> recipients = new java.util.ArrayList<>(rows.size());
        for (SentEmail e : rows) {
            if (e.getViewedAt() != null) viewed++;
            if (e.getRepliedAt() != null) replied++;
            switch (e.getStatus()) {
                case SENT -> sent++;
                case FAILED -> failed++;
            }
            recipients.add(RecipientDto.from(e));
        }
        SentEmail first = rows.get(0);
        return new SentEmailBatchDetailDto(
                batchId,
                first.getSubjectSnapshot(),
                first.getSentById(),
                first.getSentAt(),
                rows.size(),
                viewed,
                replied,
                sent,
                failed,
                recipients
        );
    }
}
