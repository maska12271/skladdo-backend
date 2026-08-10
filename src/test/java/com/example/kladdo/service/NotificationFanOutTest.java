package com.example.kladdo.service;

import com.example.kladdo.dto.ModulePermissionDto;
import com.example.kladdo.model.Notification;
import com.example.kladdo.model.NotificationType;
import com.example.kladdo.model.PermissionModule;
import com.example.kladdo.model.Role;
import com.example.kladdo.model.User;
import com.example.kladdo.repository.NotificationRepository;
import com.example.kladdo.repository.UserRepository;
import com.example.kladdo.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the notification fan-out - who actually receives an alert. This is the part the
 * scheduled producers depend on and the part that would be expensive to get wrong: over-notifying leaks
 * information to users who cannot see the underlying record, and under-notifying silently loses alerts.
 *
 * <p>The producers themselves are thin loops over a repository query, so the interesting behaviour to pin
 * down is here: permission targeting, per-user mutes, and the dedupe that stops a daily job re-alerting a
 * condition that persists.</p>
 */
class NotificationFanOutTest {

    private static final long COMPANY_ID = 7L;

    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final PermissionService permissions = mock(PermissionService.class);

    private final NotificationService service = new NotificationService(notifications, users, permissions);

    @BeforeEach
    void bindTenant() {
        // Producers run on a scheduled thread, where TenantContext.callAs has bound the company.
        TenantContext.setCompanyId(COMPANY_ID);
        when(notifications.save(any(Notification.class))).thenAnswer(call -> call.getArgument(0));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /** Builds a user and registers the permission map the service will see for them. */
    private User user(long id, Role role, boolean canViewInvoices, String muted) {
        User u = new User();
        u.setId(id);
        u.setEmail("user" + id + "@example.com");
        u.setRole(role);
        u.setArchived(false);
        u.setMutedNotificationTypes(muted);

        Map<PermissionModule, ModulePermissionDto> map = new EnumMap<>(PermissionModule.class);
        for (PermissionModule module : PermissionModule.values()) {
            map.put(module, ModulePermissionDto.none(module));
        }
        if (canViewInvoices) {
            map.put(PermissionModule.INVOICES, ModulePermissionDto.all(PermissionModule.INVOICES));
        }
        when(permissions.permissionMapFor(u)).thenReturn(map);
        return u;
    }

    private void companyHas(User... roster) {
        when(users.findByCompanyIdOrderByIdDesc(COMPANY_ID)).thenReturn(List.of(roster));
    }

    private int notifyInvoiceOverdue() {
        return service.notifyModuleViewers(PermissionModule.INVOICES, NotificationType.INVOICE_OVERDUE,
                "INV-000123", "/sales-orders/5", "INVOICE_OVERDUE:5");
    }

    @Test
    void notifiesOnlyUsersWhoCanViewTheModule() {
        User canSee = user(1, Role.USER, true, null);
        User cannotSee = user(2, Role.USER, false, null);
        companyHas(canSee, cannotSee);

        assertEquals(1, notifyInvoiceOverdue(), "only the invoice viewer is notified");
        verify(notifications).save(any(Notification.class));
    }

    @Test
    void skipsAUserWhoMutedThatType() {
        User muted = user(1, Role.USER, true, "INVOICE_OVERDUE");
        User listening = user(2, Role.USER, true, "LOW_STOCK");
        companyHas(muted, listening);

        assertEquals(1, notifyInvoiceOverdue(), "the muted user is skipped, the other still hears about it");
    }

    @Test
    void skipsAnArchivedUser() {
        User archived = user(1, Role.USER, true, null);
        archived.setArchived(true);
        companyHas(archived);

        assertEquals(0, notifyInvoiceOverdue());
        verify(notifications, never()).save(any());
    }

    @Test
    void doesNotRepeatAnAlertAlreadySentForTheSameCondition() {
        User u = user(1, Role.USER, true, null);
        companyHas(u);
        when(notifications.existsByRecipientUserIdAndDedupeKey(1L, "INVOICE_OVERDUE:5")).thenReturn(true);

        assertEquals(0, notifyInvoiceOverdue(), "a daily job must not re-alert a persisting condition");
        verify(notifications, never()).save(any());
    }

    @Test
    void writesTheAlertWithItsLinkAndDetails() {
        companyHas(user(1, Role.USER, true, null));

        assertEquals(1, notifyInvoiceOverdue());
        org.mockito.ArgumentCaptor<Notification> saved = org.mockito.ArgumentCaptor.forClass(Notification.class);
        verify(notifications).save(saved.capture());
        Notification n = saved.getValue();
        assertEquals(1L, n.getRecipientUserId());
        assertEquals(NotificationType.INVOICE_OVERDUE, n.getType());
        assertEquals("INV-000123", n.getDetails());
        assertEquals("/sales-orders/5", n.getLinkPath());
        assertEquals("INVOICE_OVERDUE:5", n.getDedupeKey());
        assertTrue(n.getReadAt() == null, "a fresh notification is unread");
    }

    /** Managers bypass module permissions, so they receive every company-wide alert. */
    @Test
    void notifiesManagersWhoBypassModulePermissions() {
        User owner = new User();
        owner.setId(9L);
        owner.setRole(Role.OWNER);
        owner.setArchived(false);
        Map<PermissionModule, ModulePermissionDto> all = new EnumMap<>(PermissionModule.class);
        for (PermissionModule m : PermissionModule.values()) {
            all.put(m, ModulePermissionDto.all(m));
        }
        when(permissions.permissionMapFor(owner)).thenReturn(all);
        companyHas(owner);

        assertEquals(1, notifyInvoiceOverdue());
    }

    @Test
    void mutedTypesParsingIgnoresBlanksAndUnknownValues() {
        User u = new User();
        u.setMutedNotificationTypes(" LOW_STOCK , , NOT_A_REAL_TYPE ,EMAIL_REPLY");

        var muted = NotificationService.mutedTypes(u);
        assertEquals(2, muted.size());
        assertTrue(muted.contains(NotificationType.LOW_STOCK));
        assertTrue(muted.contains(NotificationType.EMAIL_REPLY));
    }

    @Test
    void notifyUserByIdTargetsExactlyThatUser() {
        User u = user(3, Role.USER, false, null); // no invoice permission - direct targeting ignores modules
        when(users.findById(3L)).thenReturn(java.util.Optional.of(u));
        when(notifications.existsByRecipientUserIdAndDedupeKey(anyLong(), anyString())).thenReturn(false);

        assertTrue(service.notifyUserById(3L, NotificationType.EMAIL_REPLY, "Acme GmbH", "/emails/b1", "EMAIL_REPLY:1"));
        verify(notifications).save(any(Notification.class));
        verify(users).findById(eq(3L));
    }
}
