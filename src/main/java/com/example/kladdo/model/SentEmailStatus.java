package com.example.kladdo.model;

/**
 * Outcome of an attempt to send a {@link SentEmail}. Persisted as the audit record's terminal state:
 * {@link #SENT} when the SMTP server accepted the message, {@link #FAILED} (with a reason) otherwise.
 */
public enum SentEmailStatus {
    SENT,
    FAILED
}
