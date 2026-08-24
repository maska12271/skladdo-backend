package com.example.skladdo.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Aggregated analytics for a single service's detail page: audit trail, sales summary, a monthly time
 * series, and the order lines the service appears in.
 *
 * <p>The sales-only half of {@link ProductDetailsDto}. A service is never purchased into stock and has
 * no cost basis, so there is no purchase history, no weighted-average cost and no gross-profit figure -
 * the revenue <em>is</em> the margin as far as this page can tell.</p>
 */
public record ServiceDetailsDto(
        AuditInfo audit,
        Summary summary,
        List<MonthlyPoint> monthly,
        List<OrderLine> salesOrders
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
            int salesOrderCount
    ) {
    }

    public record MonthlyPoint(
            String month,           // "YYYY-MM"
            long unitsSold,
            BigDecimal revenue
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
