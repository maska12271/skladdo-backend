package com.example.skladdo.model;

/**
 * Whether an invoice bills the whole sale or an up-front deposit against it.
 *
 * <ul>
 *   <li>{@link #FINAL} - the normal invoice for the full order value. If a {@link #PREPAYMENT} for the
 *       same order has been paid, its amount is deducted from the balance due.</li>
 *   <li>{@link #PREPAYMENT} - a deposit invoice for a portion of the order, issued before the final
 *       invoice so the customer can pay up front.</li>
 * </ul>
 *
 * <p>Stored nullable on {@link Invoice}; a row with no value reads back as {@link #FINAL} (see
 * {@link Invoice#getType()}) so the column migrates cleanly under {@code ddl-auto=update}.</p>
 */
public enum InvoiceType {
    FINAL,
    PREPAYMENT
}
