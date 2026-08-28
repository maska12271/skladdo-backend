package com.example.skladdo.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Aggregated figures for the dashboard, computed server-side and gated per module: each block is
 * {@code null} when the current user cannot view that area, so the frontend renders only what it is
 * allowed to see. The {@code monthly} series always spans the last 12 months (zero-filled), with the
 * revenue/spend columns populated only for the sections the user can view.
 */
public record DashboardStatsDto(
        Money sales,
        Money purchases,
        ProductsBlock products,
        TendersBlock tenders,
        List<MonthlyPoint> monthly,
        List<RankRow> topClients,
        List<RankRow> topProducts,
        List<RankRow> topServices,
        List<ActivityItem> activity,
        Fulfilment fulfilment,
        ExpiryBlock expiry,
        Receivables receivables,
        // Cash actually collected from invoice payments this/last month (by paid date), independent of
        // when the orders were booked — so payments on old orders still register in a quiet sales month.
        // Reuses Money: thisMonth/lastMonth are the sums, activeCount = invoices paid this month.
        Money collected
) {

    /**
     * Outstanding customer invoices: everything unpaid, the overdue subset, the penalty accruing on it,
     * and the worst offenders. Populated only for users who can view invoices. {@code unpaidTotal} and
     * {@code overdueTotal} are principal (amount due excluding penalty); {@code penaltyAccruedTotal} is
     * the live penalty summed across overdue invoices.
     */
    public record Receivables(
            int unpaidCount,
            BigDecimal unpaidTotal,
            int overdueCount,
            BigDecimal overdueTotal,
            BigDecimal penaltyAccruedTotal,
            List<OverdueRow> topOverdue,
            // How the overdue money splits by how late it is. Covers every overdue invoice, not just the
            // few in topOverdue, so the frontend can chart the aging without a second request.
            List<AgingBucket> aging
    ) {
    }

    /** One aging band of overdue receivables. {@code bucket} is a stable key the frontend labels. */
    public record AgingBucket(
            String bucket,      // D1_30 | D31_60 | D60_PLUS
            int count,
            BigDecimal amount
    ) {
    }

    public record OverdueRow(
            Long invoiceId,
            String invoiceNumber,
            Long salesOrderId,
            String salesOrderNumber,
            String clientName,
            LocalDate dueDate,
            int daysOverdue,
            String currency,
            BigDecimal amountDue
    ) {
    }

    /**
     * Lots that are already expired or expiring within the dashboard horizon (90 days). The frontend
     * widget narrows the "expiring soon" set further by a user-selected day threshold. Populated only
     * for users who can view products.
     */
    public record ExpiryBlock(
            int expiredCount,
            List<ExpiringLotRow> rows
    ) {
    }

    public record ExpiringLotRow(
            Long productId,
            String productName,
            String lotNumber,
            String warehouseName,
            int quantity,
            LocalDate expiryDate
    ) {
    }

    /**
     * Operational, money-free view for order fulfilment (used by the warehouse dashboard): sales orders
     * still to be shipped and purchase orders still expected in. Populated only for users who can view
     * the corresponding orders.
     */
    public record Fulfilment(
            List<FulfilmentOrder> salesToShip,
            List<FulfilmentOrder> purchasesIncoming,
            int salesToShipCount,
            int purchasesIncomingCount
    ) {
    }

    public record FulfilmentOrder(
            Long id,
            String orderNumber,
            String counterpartyName,
            String status,
            LocalDate orderDate,
            LocalDate dueDate           // expected delivery date for purchases; null for sales
    ) {
    }

    /** Money totals for an order type: current vs previous month, plus active/total order counts. */
    public record Money(
            BigDecimal thisMonth,
            BigDecimal lastMonth,
            int activeCount,
            int totalCount
    ) {
    }

    public record ProductsBlock(
            int total,
            int lowStockCount,
            List<LowStockRow> lowStock
    ) {
    }

    public record LowStockRow(
            Long id,
            String name,
            String manufacturerName,
            int stockQuantity,
            int minimumStock
    ) {
    }

    /** {@code active} is the few still-running tenders the widget lists, not the newest overall. */
    public record TendersBlock(
            int activeCount,
            int totalCount,
            BigDecimal totalValue,
            List<TenderRow> active
    ) {
    }

    public record TenderRow(
            Long id,
            String title,
            String status,
            String customerName,
            BigDecimal estimatedValue
    ) {
    }

    public record MonthlyPoint(
            String month,       // "YYYY-MM"
            BigDecimal revenue,
            BigDecimal spend
    ) {
    }

    public record RankRow(
            Long id,
            String name,
            BigDecimal amount,
            long quantity
    ) {
    }

    public record ActivityItem(
            String type,        // SALE | PURCHASE | TENDER
            Long id,
            String label,
            String status,
            LocalDate date,
            BigDecimal amount
    ) {
    }
}
