package com.example.tenderapp.dto;

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
        List<ActivityItem> activity
) {

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

    public record TendersBlock(
            int activeCount,
            int totalCount,
            BigDecimal totalValue,
            List<TenderRow> latest
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
