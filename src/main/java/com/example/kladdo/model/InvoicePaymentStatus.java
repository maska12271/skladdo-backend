package com.example.kladdo.model;

/**
 * Lifecycle of a generated {@link Invoice}. An invoice is issued {@link #UNPAID}; it becomes
 * {@link #PAID} once the customer settles it, or {@link #VOID} if it was cancelled. A voided invoice is
 * kept for audit history (its number and PDF are never reused) and frees its sales order so a fresh
 * invoice can be generated.
 *
 * <p>"Overdue" is intentionally <em>not</em> a status here - it is derived live from the due date while
 * an invoice is still {@code UNPAID} (see {@code PenaltyCalculator}), so it never has to be kept in
 * sync.</p>
 */
public enum InvoicePaymentStatus {
    UNPAID,
    PAID,
    VOID
}
