package com.example.skladdo.dto;

import java.time.Instant;

/**
 * One row of the sent-emails list, representing a whole bulk send (a {@code batchId} group) rather than a
 * single recipient. Carries the aggregate counts the list needs - how many were sent/failed and, of the
 * sent ones, how many were opened and replied to.
 *
 * <p>{@code representativeId} is the id of one underlying {@link com.example.skladdo.model.SentEmail} row,
 * used to open the single-email detail directly when a batch has just one recipient. When
 * {@code recipientCount > 1} the frontend instead opens the batch detail page keyed by {@code batchKey}.
 * {@code manufacturerName}/{@code recipientEmail} are only meaningful for a single-recipient batch.</p>
 */
public record SentEmailBatchDto(
        String batchKey,
        Long representativeId,
        String subject,
        Long sentById,
        Instant sentAt,
        int recipientCount,
        int viewedCount,
        int repliedCount,
        int sentCount,
        int failedCount,
        String manufacturerName,
        String recipientEmail
) {
    /** Maps one aggregate row (see {@code SentEmailRepository#findBatches}) into the DTO. */
    public static SentEmailBatchDto fromRow(Object[] row) {
        return new SentEmailBatchDto(
                (String) row[0],
                toLong(row[1]),
                (String) row[2],
                toLong(row[3]),
                (Instant) row[4],
                toInt(row[5]),
                toInt(row[6]),
                toInt(row[7]),
                toInt(row[8]),
                toInt(row[9]),
                (String) row[10],
                (String) row[11]
        );
    }

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static int toInt(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }
}
