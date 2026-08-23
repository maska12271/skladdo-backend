package com.example.skladdo.dto;

import com.example.skladdo.model.NotificationType;

import java.util.List;

/**
 * The notification types the signed-in user wants switched off. A null/empty list means "mute nothing" -
 * the field is a wrapper type (not a primitive) so an omitted value deserializes under Jackson 3.
 */
public record UpdateNotificationPreferencesRequest(
        List<NotificationType> mutedTypes
) {
}
