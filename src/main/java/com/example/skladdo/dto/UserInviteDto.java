package com.example.skladdo.dto;

import com.example.skladdo.model.Role;
import com.example.skladdo.model.UserInvite;

import java.time.Instant;

/**
 * An outstanding (or already spent) invitation as the users page shows it, carrying the full shareable
 * URL so the page never has to rebuild it and cannot get it wrong.
 *
 * @param status ACCEPTED / REVOKED / EXPIRED / PENDING - which one it is decides what the row offers,
 *               and the reason a link stopped working is more useful than a bare boolean
 */
public record UserInviteDto(
        Long id,
        String url,
        Role role,
        boolean canSeePrices,
        String status,
        Instant expiresAt,
        Instant createdAt,
        String sentToEmail,
        Instant sentAt,
        Instant acceptedAt,
        /** The address the person actually signed up with; null until they do. */
        String acceptedEmail
) {
    public static UserInviteDto from(UserInvite invite, String url, String acceptedEmail) {
        return new UserInviteDto(
                invite.getId(),
                url,
                invite.getRole(),
                invite.isCanSeePrices(),
                status(invite),
                invite.getExpiresAt(),
                invite.getCreatedAt(),
                invite.getSentToEmail(),
                invite.getSentAt(),
                invite.getAcceptedAt(),
                acceptedEmail);
    }

    private static String status(UserInvite invite) {
        if (invite.isAccepted()) {
            return "ACCEPTED";
        }
        if (invite.isRevoked()) {
            return "REVOKED";
        }
        if (invite.isExpired(Instant.now())) {
            return "EXPIRED";
        }
        return "PENDING";
    }
}
