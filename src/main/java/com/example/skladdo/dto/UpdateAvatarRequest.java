package com.example.skladdo.dto;

import jakarta.validation.constraints.Size;

/**
 * Sets (or clears) an account's avatar. Two mutually exclusive shapes in one payload: an uploaded
 * picture's {@code avatarKey}, or a preset {@code avatarIcon} + {@code avatarColor} pair. All three
 * absent means "back to initials".
 *
 * <p>Only ever one of the two survives - {@code AuthService.updateAvatar} clears the other - so an
 * account can never be left with a photo it thought it had replaced with an icon.</p>
 */
public record UpdateAvatarRequest(
        @Size(max = 1000) String avatarKey,
        @Size(max = 40) String avatarIcon,
        @Size(max = 20) String avatarColor
) {
}
