package com.example.skladdo.controller;

import com.example.skladdo.dto.NotificationDto;
import com.example.skladdo.dto.UpdateNotificationPreferencesRequest;
import com.example.skladdo.model.NotificationType;
import com.example.skladdo.service.NotificationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The signed-in user's own notifications. Every endpoint is scoped to the caller, so this needs no
 * permission module beyond being authenticated - a user can only ever see and clear their own alerts.
 */
@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notifications")
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public List<NotificationDto> list(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return notificationService.myFeed(unreadOnly, limit);
    }

    /** Cheap endpoint the bell polls; kept separate so polling never pulls the whole feed. */
    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("count", notificationService.myUnreadCount());
    }

    @PostMapping("/{id}/read")
    public void markRead(@PathVariable Long id) {
        notificationService.markRead(id);
    }

    @PostMapping("/read-all")
    public Map<String, Integer> markAllRead() {
        return Map.of("marked", notificationService.markAllRead());
    }

    @GetMapping("/preferences")
    public Map<String, List<NotificationType>> preferences() {
        return Map.of("mutedTypes", notificationService.myMutedTypes());
    }

    @PutMapping("/preferences")
    public Map<String, List<NotificationType>> updatePreferences(
            @Valid @RequestBody UpdateNotificationPreferencesRequest request) {
        return Map.of("mutedTypes", notificationService.replaceMyMutedTypes(request.mutedTypes()));
    }
}
