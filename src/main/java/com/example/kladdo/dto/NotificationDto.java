package com.example.kladdo.dto;

import com.example.kladdo.model.Notification;
import com.example.kladdo.model.NotificationType;

import java.time.Instant;

/** Read-only view of one notification. The client renders the translated title for {@code type}. */
public record NotificationDto(
        Long id,
        NotificationType type,
        String details,
        String linkPath,
        Instant readAt,
        Instant createdAt
) {
    public static NotificationDto from(Notification n) {
        return new NotificationDto(
                n.getId(),
                n.getType(),
                n.getDetails(),
                n.getLinkPath(),
                n.getReadAt(),
                n.getCreatedAt()
        );
    }
}
