package com.example.kladdo.dto;

import java.math.BigDecimal;

/**
 * Per-order billing summary for the sales-orders list: the derived {@link
 * com.example.kladdo.model.OrderPaymentStatus} plus the money the customer still owes. Only orders that
 * have at least one invoice are returned; the frontend treats any order absent from the map as
 * {@code NOT_INVOICED}. {@code amountDue}/{@code penaltyAmount} sum the order's active invoices.
 */
public record OrderPaymentSummaryDto(
        Long orderId,
        String paymentStatus,
        boolean overdue,
        String currency,
        BigDecimal amountDue,
        BigDecimal penaltyAmount
) {
}
