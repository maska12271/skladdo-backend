package com.example.skladdo.model;

/**
 * What kind of change an {@link AuditLog} row records. Kept deliberately small: the row's
 * {@code entityType} says <em>what</em> was touched, so the action only has to say <em>how</em>.
 *
 * <p>Adding a value here later means widening the native {@code ENUM} column on an existing database -
 * see {@code SchemaMigrations} for the helper that does it.</p>
 */
public enum AuditAction {
    CREATE,
    UPDATE,
    DELETE,
    /** An order moved from one {@link OrderStatus} to another. */
    STATUS_CHANGE,
    /** A user's per-module access was rewritten. */
    PERMISSIONS_CHANGE,
    ARCHIVE,
    UNARCHIVE
}
