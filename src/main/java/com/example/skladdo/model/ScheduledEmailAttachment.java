package com.example.skladdo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One file waiting to be attached when its {@link ScheduledEmail} fires.
 *
 * <p>Holds a storage key, never a URL: the bytes live in object storage until the send, and a presigned
 * URL would have expired long before then (see {@code StorageService}). The key is deleted along with
 * the schedule row, whether it fired or was cancelled.</p>
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
public class ScheduledEmailAttachment {

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "content_type")
    private String contentType;

    public ScheduledEmailAttachment(String storageKey, String fileName, String contentType) {
        this.storageKey = storageKey;
        this.fileName = fileName;
        this.contentType = contentType;
    }
}
