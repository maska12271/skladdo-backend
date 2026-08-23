package com.example.skladdo.dto;

import com.example.skladdo.model.EmailReply;
import com.example.skladdo.model.SentEmail;

import java.time.Instant;
import java.util.List;

/**
 * Full view of a {@link SentEmail}: the list fields plus the rendered body snapshot, the failure reason
 * (if any), and the inbound reply thread.
 */
public record SentEmailDetailDto(
        Long id,
        Long manufacturerId,
        String manufacturerName,
        String recipientEmail,
        String subject,
        String body,
        String status,
        String failureReason,
        Instant sentAt,
        Long sentById,
        boolean viewed,
        Instant viewedAt,
        boolean replied,
        Instant repliedAt,
        String attachmentNames,
        List<ReplyDto> replies
) {
    public record ReplyDto(Long id, String fromAddress, String snippet, Instant receivedAt) {
        public static ReplyDto from(EmailReply r) {
            return new ReplyDto(r.getId(), r.getFromAddress(), r.getSnippet(), r.getReceivedAt());
        }
    }

    public static SentEmailDetailDto from(SentEmail e, List<EmailReply> replies) {
        return new SentEmailDetailDto(
                e.getId(),
                e.getManufacturer() != null ? e.getManufacturer().getId() : null,
                e.getManufacturerNameSnapshot(),
                e.getRecipientEmail(),
                e.getSubjectSnapshot(),
                e.getBodySnapshot(),
                e.getStatus() != null ? e.getStatus().name() : null,
                e.getFailureReason(),
                e.getSentAt(),
                e.getSentById(),
                e.getViewedAt() != null,
                e.getViewedAt(),
                e.getRepliedAt() != null,
                e.getRepliedAt(),
                e.getAttachmentNames(),
                replies.stream().map(ReplyDto::from).toList()
        );
    }
}
