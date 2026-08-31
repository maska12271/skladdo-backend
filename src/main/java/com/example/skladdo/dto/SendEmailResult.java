package com.example.skladdo.dto;

import java.util.List;

/**
 * Summary returned after a send: how many messages were accepted vs. failed, plus a per-recipient
 * breakdown so the UI can show exactly which partners failed and why.
 *
 * <p>A <em>scheduled</em> send returns the same shape with {@code sent}/{@code failed} at zero,
 * {@code results} empty and {@code scheduledId} set - nothing has been attempted yet. One response type
 * keeps the compose form to a single branch rather than two endpoints with two shapes.</p>
 */
public record SendEmailResult(
        int sent,
        int failed,
        Long scheduledId,
        List<RecipientResult> results
) {
    /** The result of a send that was queued for later rather than attempted now. */
    public static SendEmailResult scheduled(Long scheduledId) {
        return new SendEmailResult(0, 0, scheduledId, List.of());
    }

    public record RecipientResult(
            Long recipientId,
            String recipientName,
            String recipientEmail,
            String status,
            String failureReason,
            Long sentEmailId
    ) {
    }
}
