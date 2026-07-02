package com.example.kladdo.model;

/**
 * The payment state of a sales order, <em>derived</em> from its invoices (never stored). It sits
 * alongside the operational {@link OrderStatus} so an order carries two independent statuses: one for
 * fulfilment (shipped, closed…) and one for billing.
 *
 * <p>Precedence when deriving: an active (non-void) {@link InvoiceType#FINAL} invoice wins; otherwise an
 * active {@link InvoiceType#PREPAYMENT} is considered; otherwise the order is {@link #NOT_INVOICED}.</p>
 */
public enum OrderPaymentStatus {
    /** No active invoice exists for the order. */
    NOT_INVOICED,
    /** A prepayment invoice is issued and still unpaid, not yet past its due date. */
    PREPAYMENT_PENDING,
    /** A prepayment invoice is issued, unpaid and past its due date. */
    PREPAYMENT_OVERDUE,
    /** The prepayment has been paid but the final invoice has not been generated yet. */
    AWAITING_FINAL,
    /** A final invoice is issued and unpaid, not yet past its due date. */
    INVOICED,
    /** A final invoice is issued, unpaid and past its due date. */
    OVERDUE,
    /** The final invoice has been paid in full. */
    PAID
}
