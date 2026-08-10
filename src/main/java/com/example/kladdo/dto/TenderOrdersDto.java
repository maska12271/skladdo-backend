package com.example.kladdo.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The orders linked to a tender and the money they add up to, for the tender detail page. Aggregates are in
 * the company base currency (each order converted via its snapshotted exchange rate); {@code profit} is
 * {@code revenue - spending}.
 */
public record TenderOrdersDto(
        String baseCurrency,
        BigDecimal revenue,
        BigDecimal spending,
        BigDecimal profit,
        int salesCount,
        int purchaseCount,
        List<OrderRow> orders
) {
    public record OrderRow(
            Long id,
            String type,             // "SALES" | "PURCHASE"
            String orderNumber,
            String counterpartyName, // client (sales) or manufacturer (purchase)
            String status,
            LocalDate orderDate,
            BigDecimal total,        // in the order's own currency
            String currency,
            BigDecimal baseTotal     // converted to the company base currency
    ) {
    }
}
