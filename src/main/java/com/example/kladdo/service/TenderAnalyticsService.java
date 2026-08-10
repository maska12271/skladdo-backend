package com.example.kladdo.service;

import com.example.kladdo.dto.TenderAnalyticsDto;
import com.example.kladdo.dto.TenderAnalyticsDto.DeadlineRow;
import com.example.kladdo.dto.TenderAnalyticsDto.Kpis;
import com.example.kladdo.dto.TenderAnalyticsDto.MonthlyPoint;
import com.example.kladdo.dto.TenderAnalyticsDto.StatusCount;
import com.example.kladdo.dto.TenderAnalyticsDto.TopTender;
import com.example.kladdo.model.PurchaseOrder;
import com.example.kladdo.model.SalesOrder;
import com.example.kladdo.model.Tender;
import com.example.kladdo.repository.PurchaseOrderRepository;
import com.example.kladdo.repository.SalesOrderRepository;
import com.example.kladdo.repository.TenderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the {@link TenderAnalyticsDto} for the tenders dashboard. The window is selected by tender
 * {@code publishedAt}; every figure is aggregated over the tenders that fall inside it. Money is rolled up
 * in the company base currency (each amount converted via its own snapshotted rate: {@code base = amount /
 * rate}), reusing the same approach as {@link TenderOrdersService}. Datasets are a single company's, so
 * figures are aggregated in memory rather than via per-metric SQL (mirroring {@link DashboardService}).
 */
@Service
public class TenderAnalyticsService {

    // Tender statuses considered "running" (mirrors the frontend's active-status set for tenders).
    private static final Set<String> RUNNING_STATUSES = Set.of("OPEN", "PUBLISHED", "IN_PROGRESS");
    // Fixed display order for the status breakdown.
    private static final List<String> STATUS_ORDER = List.of("OPEN", "PUBLISHED", "IN_PROGRESS", "CLOSED", "CANCELLED");

    private static final int DEFAULT_MONTHS = 12; // trend span when the window is unbounded (all time)
    private static final int MAX_MONTHS = 24;     // cap on the monthly series length
    private static final int TOP_LIMIT = 5;
    private static final int DEADLINE_LIMIT = 8;

    private final TenderRepository tenderRepository;
    private final TenderPartService tenderPartService;
    private final SalesOrderRepository salesOrderRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final ExchangeRateService exchangeRateService;

    public TenderAnalyticsService(TenderRepository tenderRepository,
                                  TenderPartService tenderPartService,
                                  SalesOrderRepository salesOrderRepository,
                                  PurchaseOrderRepository purchaseOrderRepository,
                                  ExchangeRateService exchangeRateService) {
        this.tenderRepository = tenderRepository;
        this.tenderPartService = tenderPartService;
        this.salesOrderRepository = salesOrderRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.exchangeRateService = exchangeRateService;
    }

    // Writable (not read-only): ensureParts lazily migrates legacy tenders to the parts model, matching how
    // the list/detail endpoints behave, so part counts are consistent regardless of what was viewed before.
    @Transactional
    public TenderAnalyticsDto getAnalytics(LocalDate from, LocalDate to) {
        String base = exchangeRateService.baseCurrency();

        List<Tender> inWindow = tenderRepository.findAll().stream()
                .filter(t -> inWindow(t.getPublishedAt(), from, to))
                .toList();

        // Rollups are reused for both the KPI aggregation and the top-by-value list, so compute once.
        Map<Long, TenderPartService.Rollup> rollups = new HashMap<>();

        int running = 0, participatingTenders = 0, wonTenders = 0;
        int wonParts = 0, lostParts = 0, pendingParts = 0;
        BigDecimal estimatedValueTotal = BigDecimal.ZERO;
        Map<String, Integer> statusCounts = new HashMap<>();

        for (Tender t : inWindow) {
            if (isRunning(t.getStatus())) running++;
            statusCounts.merge(t.getStatus() == null ? "UNKNOWN" : t.getStatus(), 1, Integer::sum);
            estimatedValueTotal = estimatedValueTotal.add(toBase(t.getEstimatedValue(), t.getExchangeRate()));

            tenderPartService.ensureParts(t); // migrate legacy tenders so the rollup sees their parts
            TenderPartService.Rollup r = tenderPartService.rollup(t.getId());
            rollups.put(t.getId(), r);
            wonParts += r.won();
            lostParts += r.lost();
            pendingParts += r.pending();
            if (r.participating() > 0) participatingTenders++;
            if (r.won() > 0) wonTenders++;
        }

        Double winRate = (wonParts + lostParts) > 0
                ? BigDecimal.valueOf(wonParts * 100.0 / (wonParts + lostParts)).setScale(1, RoundingMode.HALF_UP).doubleValue()
                : null;

        // ---- monthly trend --------------------------------------------------------------------------
        YearMonth end = to != null ? YearMonth.from(to) : YearMonth.now();
        YearMonth start = from != null ? YearMonth.from(from) : end.minusMonths(DEFAULT_MONTHS - 1L);
        // Bound the series length so an all-time / very wide window doesn't produce a huge chart.
        YearMonth earliest = end.minusMonths(MAX_MONTHS - 1L);
        if (start.isBefore(earliest)) start = earliest;

        Map<YearMonth, int[]> countByMonth = new HashMap<>();   // [count, won, parts, wonParts]
        Map<YearMonth, BigDecimal> revByMonth = new HashMap<>();
        Map<YearMonth, BigDecimal> spendByMonth = new HashMap<>();
        Map<Long, YearMonth> tenderMonth = new HashMap<>();

        for (Tender t : inWindow) {
            YearMonth ym = t.getPublishedAt() != null ? YearMonth.from(t.getPublishedAt()) : null;
            if (ym == null) continue;
            tenderMonth.put(t.getId(), ym);
            if (ym.isBefore(start) || ym.isAfter(end)) continue;
            int[] cell = countByMonth.computeIfAbsent(ym, k -> new int[4]);
            cell[0]++;
            TenderPartService.Rollup r = rollups.get(t.getId());
            if (r != null) {
                if (r.won() > 0) cell[1]++;
                cell[2] += r.partCount();
                cell[3] += r.won();
            }
        }

        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal spending = BigDecimal.ZERO;
        for (SalesOrder o : salesOrderRepository.findAll()) {
            YearMonth ym = tenderMonth.get(o.getTenderId());
            if (ym == null) continue; // order not linked to an in-window tender (or tender has no publish month)
            BigDecimal amount = toBase(o.getTotalAmount(), o.getExchangeRate());
            revenue = revenue.add(amount);
            if (!ym.isBefore(start) && !ym.isAfter(end)) revByMonth.merge(ym, amount, BigDecimal::add);
        }
        for (PurchaseOrder o : purchaseOrderRepository.findAll()) {
            YearMonth ym = tenderMonth.get(o.getTenderId());
            if (ym == null) continue;
            BigDecimal amount = toBase(o.getTotalAmount(), o.getExchangeRate());
            spending = spending.add(amount);
            if (!ym.isBefore(start) && !ym.isAfter(end)) spendByMonth.merge(ym, amount, BigDecimal::add);
        }

        List<MonthlyPoint> monthly = new ArrayList<>();
        for (YearMonth ym = start; !ym.isAfter(end); ym = ym.plusMonths(1)) {
            int[] cell = countByMonth.getOrDefault(ym, new int[4]);
            monthly.add(new MonthlyPoint(
                    ym.toString(),
                    cell[0],
                    cell[1],
                    cell[2],
                    cell[3],
                    revByMonth.getOrDefault(ym, BigDecimal.ZERO),
                    spendByMonth.getOrDefault(ym, BigDecimal.ZERO)
            ));
        }

        // ---- status breakdown (fixed order, then any leftover statuses) -----------------------------
        List<StatusCount> statusBreakdown = new ArrayList<>();
        for (String status : STATUS_ORDER) {
            Integer c = statusCounts.remove(status);
            if (c != null && c > 0) statusBreakdown.add(new StatusCount(status, c));
        }
        statusCounts.forEach((status, c) -> {
            if (c > 0) statusBreakdown.add(new StatusCount(status, c));
        });

        // ---- upcoming deadlines ---------------------------------------------------------------------
        LocalDate today = LocalDate.now();
        List<DeadlineRow> upcomingDeadlines = inWindow.stream()
                .filter(t -> t.getDeadline() != null && !t.getDeadline().isBefore(today))
                .sorted(Comparator.comparing(Tender::getDeadline))
                .limit(DEADLINE_LIMIT)
                .map(t -> new DeadlineRow(t.getId(), t.getTitle(), t.getDeadline(), t.getStatus(),
                        toBase(t.getEstimatedValue(), t.getExchangeRate())))
                .toList();

        // ---- top tenders by (base) value ------------------------------------------------------------
        List<TopTender> topByValue = inWindow.stream()
                .sorted(Comparator.comparing((Tender t) -> toBase(t.getEstimatedValue(), t.getExchangeRate())).reversed())
                .limit(TOP_LIMIT)
                .map(t -> new TopTender(t.getId(), t.getTitle(), t.getStatus(),
                        toBase(t.getEstimatedValue(), t.getExchangeRate()),
                        rollups.getOrDefault(t.getId(), EMPTY_ROLLUP).won()))
                .toList();

        Kpis kpis = new Kpis(inWindow.size(), running, participatingTenders, wonTenders,
                wonParts, lostParts, pendingParts, winRate,
                estimatedValueTotal, revenue, spending, revenue.subtract(spending));

        return new TenderAnalyticsDto(base, kpis, monthly, statusBreakdown, upcomingDeadlines, topByValue);
    }

    private static final TenderPartService.Rollup EMPTY_ROLLUP = new TenderPartService.Rollup(0, 0, 0, 0, 0, 0.0);

    private static boolean inWindow(LocalDate published, LocalDate from, LocalDate to) {
        if (from == null && to == null) return true;
        if (published == null) return false; // dated window excludes tenders with no publish date
        if (from != null && published.isBefore(from)) return false;
        return to == null || !published.isAfter(to);
    }

    private static boolean isRunning(String status) {
        return status != null && RUNNING_STATUSES.contains(status.toUpperCase());
    }

    /** Delegates to the shared converter so every roll-up in the app uses one implementation. */
    private static BigDecimal toBase(Double amount, BigDecimal rate) {
        return MoneyConverter.toBase(amount, rate);
    }

    private static BigDecimal toBase(BigDecimal amount, BigDecimal rate) {
        return MoneyConverter.toBase(amount, rate);
    }
}
