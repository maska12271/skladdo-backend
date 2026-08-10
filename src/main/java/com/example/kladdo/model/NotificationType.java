package com.example.kladdo.model;

/**
 * What a {@link Notification} is about. Each type also decides who receives it - see
 * {@code NotificationService.recipientsFor} - and is the key a user mutes in their own preferences.
 *
 * <p>Adding a value here later means widening the native {@code ENUM} column on an existing database;
 * see {@code SchemaMigrations}.</p>
 */
public enum NotificationType {
    /** An issued invoice passed its due date without being paid. */
    INVOICE_OVERDUE,
    /** A tender's submission deadline is approaching. */
    TENDER_DEADLINE,
    /** A product's stock fell below its configured minimum. */
    LOW_STOCK,
    /** A manufacturer replied to an email this user sent. */
    EMAIL_REPLY
}
