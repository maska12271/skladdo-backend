package com.example.skladdo.dto;

import java.util.List;

/**
 * Summary returned after a (single or bulk) send: how many messages were accepted vs. failed, plus a
 * per-recipient breakdown so the UI can show exactly which manufacturers failed and why.
 */
public record SendEmailResult(
        int sent,
        int failed,
        List<RecipientResult> results
) {
    public record RecipientResult(
            Long manufacturerId,
            String manufacturerName,
            String recipientEmail,
            String status,
            String failureReason,
            Long sentEmailId
    ) {
    }
}
