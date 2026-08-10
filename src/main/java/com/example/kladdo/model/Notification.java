package com.example.kladdo.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;

/**
 * One in-app alert for one user. {@code @TenantId}-scoped, and additionally addressed to a single
 * {@link #recipientUserId} - a notification is always personal, unlike the company-wide {@link AuditLog}.
 *
 * <p>Like the audit log, the stored text is <strong>language-neutral</strong>: {@link #details} holds an
 * invoice number or product name, and the UI renders the translated title for the {@link #type}. A row
 * written while the app was in Estonian therefore still reads correctly in English.</p>
 */
@Entity
@Table(indexes = {
        // The bell polls "unread for me" constantly, and every producer checks the dedupe key first.
        @Index(name = "idx_notification_recipient_read", columnList = "recipientUserId, readAt"),
        @Index(name = "idx_notification_dedupe", columnList = "recipientUserId, dedupeKey")
})
@Getter
@Setter
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    @Column(nullable = false, updatable = false)
    private Long recipientUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private NotificationType type;

    /** Language-neutral detail: an invoice number, a product name, a tender title. */
    @Column(updatable = false, length = 500)
    private String details;

    /** In-app path this notification links to, e.g. {@code "/sales-orders/12"}. */
    @Column(updatable = false, length = 200)
    private String linkPath;

    /**
     * Identifies the condition behind this notification, e.g. {@code "INVOICE_OVERDUE:42"}. A producer
     * skips a recipient that already has a row with this key, so a job that runs daily over a condition
     * that persists for weeks still only alerts once.
     *
     * <p>The flip side: once a condition has been alerted it is never re-alerted for that user, even after
     * they read and clear it. Re-notifying on a recurrence is a deliberate non-goal for now - it needs a
     * "condition cleared" signal that none of the current producers have.</p>
     */
    @Column(updatable = false, length = 120)
    private String dedupeKey;

    /** When the recipient marked it read. Null while unread - the bell counts these. */
    private Instant readAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
