package com.example.kladdo.service;

import com.example.kladdo.dto.ModulePermissionDto;
import com.example.kladdo.dto.NotificationDto;
import com.example.kladdo.model.Notification;
import com.example.kladdo.model.NotificationType;
import com.example.kladdo.model.PermissionModule;
import com.example.kladdo.model.User;
import com.example.kladdo.repository.NotificationRepository;
import com.example.kladdo.repository.UserRepository;
import com.example.kladdo.security.SecurityUtil;
import com.example.kladdo.security.TenantContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * In-app notifications: fan-out from the producers (scheduled jobs and event hooks) to the users who
 * should see each alert, plus the reads the bell performs.
 *
 * <p><strong>Fan-out</strong> is by permission: an alert about invoices goes to everyone who can view
 * invoices, which already includes owners and administrators (they bypass module permissions). A user who
 * muted the type is skipped, and {@link Notification#getDedupeKey()} stops a daily job from re-alerting a
 * condition that persists.</p>
 *
 * <p>Producers run on scheduled threads, so the tenant must already be bound by
 * {@link TenantContext#callAs} before these methods are called - see {@code ScheduledMaintenanceService}.</p>
 */
@Service
public class NotificationService {

    /** Hard cap on how many rows the bell will ever pull in one request. */
    private static final int MAX_FEED = 50;

    private final NotificationRepository repository;
    private final UserRepository userRepository;
    private final PermissionService permissionService;

    public NotificationService(NotificationRepository repository,
                               UserRepository userRepository,
                               PermissionService permissionService) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.permissionService = permissionService;
    }

    // --- Producing --------------------------------------------------------------------------------

    /**
     * Notifies every user in the current company who can view {@code module}. Returns how many rows were
     * actually written (mutes and dedupe suppress the rest), which the jobs use for their log line.
     */
    @Transactional
    public int notifyModuleViewers(PermissionModule module,
                                   NotificationType type,
                                   String details,
                                   String linkPath,
                                   String dedupeKey) {
        int created = 0;
        for (User user : userRepository.findByCompanyIdOrderByIdDesc(currentCompanyId())) {
            if (canView(user, module) && notifyUser(user, type, details, linkPath, dedupeKey)) {
                created++;
            }
        }
        return created;
    }

    /**
     * Notifies one specific user (used where the audience is a single person, e.g. the sender of the email
     * that just got a reply). Returns whether a row was written.
     */
    @Transactional
    public boolean notifyUserById(Long userId, NotificationType type, String details, String linkPath, String dedupeKey) {
        return userRepository.findById(userId)
                .map(user -> notifyUser(user, type, details, linkPath, dedupeKey))
                .orElse(false);
    }

    private boolean notifyUser(User user, NotificationType type, String details, String linkPath, String dedupeKey) {
        if (isMuted(user, type)) {
            return false;
        }
        if (dedupeKey != null && repository.existsByRecipientUserIdAndDedupeKey(user.getId(), dedupeKey)) {
            return false;
        }
        Notification notification = new Notification();
        notification.setRecipientUserId(user.getId());
        notification.setType(type);
        notification.setDetails(details);
        notification.setLinkPath(linkPath);
        notification.setDedupeKey(dedupeKey);
        notification.setCreatedAt(Instant.now());
        repository.save(notification);
        return true;
    }

    /** Archived accounts are excluded: they cannot sign in, so an alert for them would never be seen. */
    private boolean canView(User user, PermissionModule module) {
        if (Boolean.TRUE.equals(user.getArchived())) {
            return false;
        }
        ModulePermissionDto permission = permissionService.permissionMapFor(user).get(module);
        return permission != null && permission.canView();
    }

    // --- Preferences ------------------------------------------------------------------------------

    /** The notification types this user has switched off. */
    public static Set<NotificationType> mutedTypes(User user) {
        String raw = user.getMutedNotificationTypes();
        if (raw == null || raw.isBlank()) {
            return EnumSet.noneOf(NotificationType.class);
        }
        Set<NotificationType> muted = EnumSet.noneOf(NotificationType.class);
        for (String part : raw.split(",")) {
            String name = part.trim();
            if (name.isEmpty()) {
                continue;
            }
            // Silently drop a value this build no longer knows, so an old row never breaks the fan-out.
            Arrays.stream(NotificationType.values())
                    .filter(t -> t.name().equals(name))
                    .findFirst()
                    .ifPresent(muted::add);
        }
        return muted;
    }

    private static boolean isMuted(User user, NotificationType type) {
        return mutedTypes(user).contains(type);
    }

    /** The signed-in user's muted types. */
    @Transactional(readOnly = true)
    public List<NotificationType> myMutedTypes() {
        return List.copyOf(mutedTypes(currentUser()));
    }

    /** Replaces the signed-in user's muted types and returns the stored set. */
    @Transactional
    public List<NotificationType> replaceMyMutedTypes(List<NotificationType> types) {
        User user = currentUser();
        Set<NotificationType> muted = types == null || types.isEmpty()
                ? EnumSet.noneOf(NotificationType.class)
                : EnumSet.copyOf(types);
        user.setMutedNotificationTypes(muted.isEmpty() ? null
                : muted.stream().map(Enum::name).reduce((a, b) -> a + "," + b).orElse(null));
        userRepository.save(user);
        return List.copyOf(muted);
    }

    // --- Reading (the bell) -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<NotificationDto> myFeed(boolean unreadOnly, int limit) {
        Long userId = SecurityUtil.currentUserId();
        PageRequest page = PageRequest.of(0, Math.min(Math.max(limit, 1), MAX_FEED));
        List<Notification> rows = unreadOnly
                ? repository.findByRecipientUserIdAndReadAtIsNullOrderByCreatedAtDesc(userId, page)
                : repository.findByRecipientUserIdOrderByCreatedAtDesc(userId, page);
        return rows.stream().map(NotificationDto::from).toList();
    }

    @Transactional(readOnly = true)
    public long myUnreadCount() {
        return repository.countByRecipientUserIdAndReadAtIsNull(SecurityUtil.currentUserId());
    }

    /** Marks one of the caller's own notifications read. Silently ignores anyone else's row. */
    @Transactional
    public void markRead(Long id) {
        Long userId = SecurityUtil.currentUserId();
        repository.findById(id)
                .filter(n -> n.getRecipientUserId().equals(userId) && n.getReadAt() == null)
                .ifPresent(n -> {
                    n.setReadAt(Instant.now());
                    repository.save(n);
                });
    }

    @Transactional
    public int markAllRead() {
        return repository.markAllRead(SecurityUtil.currentUserId(), Instant.now());
    }

    private User currentUser() {
        return userRepository.findById(SecurityUtil.currentUserId())
                .orElseThrow(() -> new IllegalStateException("No such user"));
    }

    /**
     * The company the fan-out runs for. Producers bind it via {@link TenantContext#callAs}; a request-time
     * caller has it from the JWT filter.
     */
    private static Long currentCompanyId() {
        return TenantContext.getCompanyId();
    }
}
