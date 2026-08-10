package com.example.kladdo.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Aggregated figures for the tenders dashboard, over a date window filtered by tender {@code publishedAt}.
 * Money fields are rolled up in the company base currency; the frontend hides them from users who cannot
 * see prices (mirroring the rest of the app), so the endpoint always returns them.
 */
public record TenderAnalyticsDto(
        String baseCurrency,
        Kpis kpis,
        List<MonthlyPoint> monthly,
        List<StatusCount> statusBreakdown,
        List<DeadlineRow> upcomingDeadlines,
        List<TopTender> topByValue
) {

    /** Headline figures across the window's tenders. {@code winRate} is a percent, null when nothing is decided. */
    public record Kpis(
            int totalTenders,
            int runningTenders,
            int participatingTenders,
            int wonTenders,
            int wonParts,
            int lostParts,
            int pendingParts,
            Double winRate,
            BigDecimal estimatedValueTotal,
            BigDecimal revenue,
            BigDecimal spending,
            BigDecimal profit
    ) {
    }

    /** One month of the trend series (zero-filled across the resolved window). */
    public record MonthlyPoint(
            String month,       // "YYYY-MM"
            int count,          // tenders published this month
            int won,            // of those, tenders we won at least one part of
            int parts,          // total parts (lots) across those tenders
            int wonParts,       // of those parts, ones we won
            BigDecimal revenue, // linked sales-order revenue attributed to this month
            BigDecimal spend    // linked purchase-order spend attributed to this month
    ) {
    }

    public record StatusCount(String status, int count) {
    }

    public record DeadlineRow(Long id, String title, LocalDate deadline, String status, BigDecimal value) {
    }

    public record TopTender(Long id, String title, String status, BigDecimal value, int wonCount) {
    }
}
