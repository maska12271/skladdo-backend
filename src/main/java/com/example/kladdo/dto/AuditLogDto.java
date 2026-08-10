package com.example.kladdo.dto;

import com.example.kladdo.model.AuditAction;
import com.example.kladdo.model.AuditLog;

import java.time.Instant;

/** Read-only view of one audit row. Output-only, so primitives/records need no Jackson wrapper types. */
public record AuditLogDto(
        Long id,
        Instant createdAt,
        AuditAction action,
        String entityType,
        Long entityId,
        String details,
        Long actorUserId,
        String actorName
) {
    public static AuditLogDto from(AuditLog log) {
        return new AuditLogDto(
                log.getId(),
                log.getCreatedAt(),
                log.getAction(),
                log.getEntityType(),
                log.getEntityId(),
                log.getDetails(),
                log.getActorUserId(),
                log.getActorName()
        );
    }
}
