package com.example.tenderapp.service;

import com.example.tenderapp.dto.DashboardStatsDto;
import com.example.tenderapp.dto.DashboardStatsDto.*;
import com.example.tenderapp.model.*;
import com.example.tenderapp.repository.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * Builds the {@link DashboardStatsDto} for the landing page. Every block is gated through
 * {@link PermissionService#canView}: a user only ever receives figures for the modules they are
 * allowed to see, so the same endpoint safely serves owners and narrowly-scoped staff alike.
 *
 * <p>Read-only and transactional so lazy associations (order -> client/manufacturer, item -> product)
 * can be traversed with open-in-view disabled. Datasets are small (a single company's catalogue and
 * orders), so figures are aggregated in memory rather than via per-metric SQL.</p>
 */
@Service
public class DashboardService {

    private static final int MONTHS = 12;
    private static final int TOP_LIMIT = 5;
    private static final int ACTIVITY_LIMIT = 10;
    private static final int LATEST_TENDERS = 5;

    // Order/tender statuses considered "active" — mirrors the frontend's isActiveStatus().
    private static final Set<String> ACTIVE_STATUSES = Set.of("NEW", "OPEN", "IN_PROGRESS", "PUBLISHED", "CONFIRMED");

    private final PermissionService permissionService;
    private final ProductRepository productRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final SalesOrderItemRepository salesOrderItemRepository;
    private final TenderRepository tenderRepository;

    public DashboardService(PermissionService permissionService,
                            ProductRepository productRepository,
                            SalesOrderRepository salesOrderRepository,
                            PurchaseOrderRepository purchaseOrderRepository,
                            SalesOrderItemRepository salesOrderItemRepository,
                            TenderRepository tenderRepository) {
        this.permissionService = permissionService;
        this.productRepository = productRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.salesOrderItemRepository = salesOrderItemRepository;
        this.tenderRepository = tenderRepository;
    }

    @Transactional(readOnly = true)
    public DashboardStatsDto getStats(Authentication auth) {
        boolean canSales = permissionService.canView(auth, "SALES_ORDERS");
        boolean canPurchases = permissionService.canView(auth, "PURCHASE_ORDERS");
        boolean canProducts = permissionService.canView(auth, "PRODUCTS");
        boolean canTenders = permissionService.canView(auth, "TENDERS");

        YearMonth thisMonth = YearMonth.now();
        YearMonth lastMonth = thisMonth.minusMonths(1);
        LocalDate windowStart = thisMonth.minusMonths(MONTHS - 1L).atDay(1);
        LocalDate monthStart = thisMonth.atDay(1);

        List<SalesOrder> salesOrders = canSales ? salesOrderRepository.findAll() : List.of();
        List<PurchaseOrder> purchaseOrders = canPurchases ? purchaseOrderRepository.findAll() : List.of();
        List<Product> products = canProducts ? productRepository.findAll() : List.of();
        List<Tender> tenders = canTenders ? tenderRepository.findAll() : List.of();

        // ---- monthly series (zero-filled for the whole window) ----------------------------------
        Map<YearMonth, BigDecimal> revenueByMonth = new HashMap<>();
        Map<YearMonth, BigDecimal> spendByMonth = new HashMap<>();
        for (SalesOrder o : salesOrders) {
            if (isCancelled(o.getStatus())) continue;
            YearMonth ym = monthOf(o.getOrderDate());
            if (ym != null) revenueByMonth.merge(ym, nz(o.getTotalAmount()), BigDecimal::add);
        }
        for (PurchaseOrder o : purchaseOrders) {
            if (isCancelled(o.getStatus())) continue;
            YearMonth ym = monthOf(o.getOrderDate());
            if (ym != null) spendByMonth.merge(ym, nz(o.getTotalAmount()), BigDecimal::add);
        }
        List<MonthlyPoint> monthly = new ArrayList<>();
        for (int i = MONTHS - 1; i >= 0; i--) {
            YearMonth ym = thisMonth.minusMonths(i);
            monthly.add(new MonthlyPoint(
                    ym.toString(),
                    revenueByMonth.getOrDefault(ym, BigDecimal.ZERO),
                    spendByMonth.getOrDefault(ym, BigDecimal.ZERO)
            ));
        }

        // ---- sales / purchase money blocks ------------------------------------------------------
        Money sales = canSales ? money(salesOrders, SalesOrder::getOrderDate, SalesOrder::getTotalAmount,
                o -> o.getStatus() != null ? o.getStatus().name() : null, thisMonth, lastMonth) : null;
        Money purchases = canPurchases ? money(purchaseOrders, PurchaseOrder::getOrderDate, PurchaseOrder::getTotalAmount,
                o -> o.getStatus() != null ? o.getStatus().name() : null, thisMonth, lastMonth) : null;

        // ---- products block ---------------------------------------------------------------------
        ProductsBlock productsBlock = null;
        if (canProducts) {
            List<LowStockRow> lowStock = products.stream()
                    .filter(p -> nz(p.getStockQuantity()) < nz(p.getMinimumStock()))
                    .sorted(Comparator.comparingInt(p -> nz(p.getStockQuantity()) - nz(p.getMinimumStock())))
                    .map(p -> new LowStockRow(
                            p.getId(),
                            p.getName(),
                            p.getManufacturer() != null ? p.getManufacturer().getName() : null,
                            nz(p.getStockQuantity()),
                            nz(p.getMinimumStock())
                    ))
                    .toList();
            productsBlock = new ProductsBlock(products.size(), lowStock.size(), lowStock);
        }

        // ---- tenders block ----------------------------------------------------------------------
        TendersBlock tendersBlock = null;
        if (canTenders) {
            int activeTenders = (int) tenders.stream().filter(t -> isActive(t.getStatus())).count();
            BigDecimal totalValue = tenders.stream()
                    .map(t -> t.getEstimatedValue() != null ? BigDecimal.valueOf(t.getEstimatedValue()) : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            List<TenderRow> latest = tenders.stream()
                    .sorted(Comparator.comparing(Tender::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(LATEST_TENDERS)
                    .map(t -> new TenderRow(
                            t.getId(),
                            t.getTitle(),
                            t.getStatus(),
                            tenderCustomer(t),
                            t.getEstimatedValue() != null ? BigDecimal.valueOf(t.getEstimatedValue()) : null
                    ))
                    .toList();
            tendersBlock = new TendersBlock(activeTenders, tenders.size(), totalValue, latest);
        }

        // ---- top clients (revenue this month) ---------------------------------------------------
        List<RankRow> topClients = List.of();
        if (canSales) {
            Map<Long, RankAccumulator> byClient = new HashMap<>();
            for (SalesOrder o : salesOrders) {
                if (isCancelled(o.getStatus())) continue;
                if (!inMonth(o.getOrderDate(), thisMonth) || o.getClient() == null) continue;
                byClient.computeIfAbsent(o.getClient().getId(), k -> new RankAccumulator(o.getClient().getName()))
                        .add(nz(o.getTotalAmount()), 1);
            }
            topClients = rank(byClient);
        }

        // ---- top products (units sold this month) -----------------------------------------------
        List<RankRow> topProducts = List.of();
        if (canSales) {
            Map<Long, RankAccumulator> byProduct = new HashMap<>();
            for (SalesOrderItem item : salesOrderItemRepository.findBySalesOrder_OrderDateGreaterThanEqual(monthStart)) {
                SalesOrder so = item.getSalesOrder();
                if (so != null && isCancelled(so.getStatus())) continue;
                Product p = item.getProduct();
                if (p == null) continue;
                byProduct.computeIfAbsent(p.getId(), k -> new RankAccumulator(p.getName()))
                        .add(nz(item.getLineTotal()), nz(item.getQuantity()));
            }
            topProducts = rank(byProduct);
        }

        // ---- combined recent activity -----------------------------------------------------------
        List<ActivityItem> activity = new ArrayList<>();
        if (canSales) {
            for (SalesOrder o : salesOrders) {
                activity.add(new ActivityItem("SALE", o.getId(),
                        o.getClient() != null ? o.getClient().getName() : o.getOrderNumber(),
                        o.getStatus() != null ? o.getStatus().name() : null,
                        o.getOrderDate(), nz(o.getTotalAmount())));
            }
        }
        if (canPurchases) {
            for (PurchaseOrder o : purchaseOrders) {
                activity.add(new ActivityItem("PURCHASE", o.getId(),
                        o.getManufacturer() != null ? o.getManufacturer().getName() : o.getOrderNumber(),
                        o.getStatus() != null ? o.getStatus().name() : null,
                        o.getOrderDate(), nz(o.getTotalAmount())));
            }
        }
        if (canTenders) {
            for (Tender t : tenders) {
                activity.add(new ActivityItem("TENDER", t.getId(), t.getTitle(), t.getStatus(),
                        t.getPublishedAt(),
                        t.getEstimatedValue() != null ? BigDecimal.valueOf(t.getEstimatedValue()) : BigDecimal.ZERO));
            }
        }
        activity = activity.stream()
                .sorted(Comparator.comparing(ActivityItem::date, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(ACTIVITY_LIMIT)
                .toList();

        return new DashboardStatsDto(sales, purchases, productsBlock, tendersBlock, monthly, topClients, topProducts, activity);
    }

    // ---------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------

    private <T> Money money(List<T> orders,
                            java.util.function.Function<T, LocalDate> dateOf,
                            java.util.function.Function<T, BigDecimal> amountOf,
                            java.util.function.Function<T, String> statusOf,
                            YearMonth thisMonth, YearMonth lastMonth) {
        BigDecimal thisTotal = BigDecimal.ZERO;
        BigDecimal lastTotal = BigDecimal.ZERO;
        int active = 0;
        for (T o : orders) {
            String status = statusOf.apply(o);
            // Cancelled orders aren't real revenue/spend, so they're excluded from the money totals.
            if (isCancelled(status)) continue;
            LocalDate date = dateOf.apply(o);
            BigDecimal amount = nz(amountOf.apply(o));
            if (inMonth(date, thisMonth)) thisTotal = thisTotal.add(amount);
            else if (inMonth(date, lastMonth)) lastTotal = lastTotal.add(amount);
            if (isActive(status)) active++;
        }
        return new Money(thisTotal, lastTotal, active, orders.size());
    }

    private List<RankRow> rank(Map<Long, RankAccumulator> map) {
        return map.entrySet().stream()
                .sorted((a, b) -> b.getValue().amount.compareTo(a.getValue().amount))
                .limit(TOP_LIMIT)
                .map(e -> new RankRow(e.getKey(), e.getValue().name, e.getValue().amount, e.getValue().quantity))
                .toList();
    }

    private static String tenderCustomer(Tender t) {
        if (t.getCustomerName() != null && !t.getCustomerName().isBlank()) return t.getCustomerName();
        return t.getClient() != null ? t.getClient().getName() : null;
    }

    private static boolean isActive(String status) {
        return status != null && ACTIVE_STATUSES.contains(status.toUpperCase());
    }

    private static boolean isCancelled(OrderStatus status) {
        return status == OrderStatus.CANCELLED;
    }

    private static boolean isCancelled(String status) {
        return status != null && status.equalsIgnoreCase("CANCELLED");
    }

    private static boolean inMonth(LocalDate date, YearMonth month) {
        return date != null && YearMonth.from(date).equals(month);
    }

    private static YearMonth monthOf(LocalDate date) {
        return date != null ? YearMonth.from(date) : null;
    }

    private static int nz(Integer value) {
        return value != null ? value : 0;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /** Mutable accumulator for the top-N rankings. */
    private static final class RankAccumulator {
        private final String name;
        private BigDecimal amount = BigDecimal.ZERO;
        private long quantity = 0;

        RankAccumulator(String name) {
            this.name = name;
        }

        void add(BigDecimal amount, long quantity) {
            this.amount = this.amount.add(amount);
            this.quantity += quantity;
        }
    }
}
