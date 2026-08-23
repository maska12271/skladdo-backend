package com.example.skladdo.dto;

import com.example.skladdo.model.InviteLink;

import java.time.Instant;

/**
 * An invite link as the operator's list shows it, including the full shareable URL so the panel never has
 * to reconstruct it (and cannot get it wrong).
 *
 * @param status  REVOKED / EXPIRED / EXHAUSTED / ACTIVE - the reason a link is unusable is more useful
 *                than a bare boolean, since the fix differs for each
 * @param signups how many companies actually came through it
 */
public record InviteLinkDto(
        Long id,
        String code,
        String url,
        String label,
        String accountType,
        String plan,
        Integer freeDays,
        Instant expiresAt,
        Integer maxUses,
        int usedCount,
        long signups,
        String status,
        Instant createdAt
) {
    public static InviteLinkDto from(InviteLink link, String url, long signups) {
        return new InviteLinkDto(
                link.getId(), link.getCode(), url, link.getLabel(),
                link.getAccountType() == null ? null : link.getAccountType().name(),
                link.getPlan() == null ? null : link.getPlan().name(),
                link.getFreeDays(), link.getExpiresAt(), link.getMaxUses(), link.getUsedCount(),
                signups, status(link), link.getCreatedAt());
    }

    private static String status(InviteLink link) {
        if (!link.isActive()) {
            return "REVOKED";
        }
        if (link.isExpired(Instant.now())) {
            return "EXPIRED";
        }
        if (link.isExhausted()) {
            return "EXHAUSTED";
        }
        return "ACTIVE";
    }
}
