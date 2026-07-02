package com.example.kladdo.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Aggregated analytics for a single product's detail page: audit trail, sales/purchase
 * summary, a monthly time series, and the order lines the product appears in.
 */
public record ProductDetailsDto(
        AuditInfo audit,
        Summary summary,
        List<MonthlyPoint> monthly,
        List<OrderLine> salesOrders,
        List<OrderLine> purchaseOrders
) {

    public record Actor(Long id, String name) {
    }

    public record AuditInfo(
            Actor createdBy,
            Instant createdAt,
            Actor updatedBy,
            Instant updatedAt
    ) {
    }

    public record Summary(
            long totalUnitsSold,
            BigDecimal totalRevenue,
            int salesOrderCount,
            long totalUnitsPurchased,
            BigDecimal totalPurchaseCost,
            int purchaseOrderCount,
            BigDecimal weightedAvgPurchaseCost,
            BigDecimal grossProfit
    ) {
    }

    public record MonthlyPoint(
            String month,           // "YYYY-MM"
            long unitsSold,
            BigDecimal revenue,
            long unitsPurchased
    ) {
    }

    public record OrderLine(
            Long orderId,
            String orderNumber,
            LocalDate orderDate,
            String status,
            String counterpartyName,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal
    ) {
    }
}
