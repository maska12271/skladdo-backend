package com.example.kladdo.service;

import com.example.kladdo.dto.DashboardStatsDto;
import com.example.kladdo.dto.DashboardStatsDto.*;
import com.example.kladdo.model.*;
import com.example.kladdo.repository.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
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
    private static final int LOW_STOCK_LIMIT = 10;

    // Order/tender statuses considered "active" — mirrors the frontend's isActiveStatus().
    private static final Set<String> ACTIVE_STATUSES = Set.of("NEW", "OPEN", "IN_PROGRESS", "PUBLISHED", "CONFIRMED");

    // Orders still open for fulfilment: not yet shipped, closed or cancelled.
    private static final Set<OrderStatus> OPEN_FOR_FULFILMENT =
            EnumSet.of(OrderStatus.NEW, OrderStatus.IN_PROGRESS, OrderStatus.CONFIRMED);
    private static final int FULFILMENT_LIMIT = 25;

    private static final int EXPIRY_HORIZON_DAYS = 90;

    private final PermissionService permissionService;
    private final ProductRepository productRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final SalesOrderItemRepository salesOrderItemRepository;
    private final TenderRepository tenderRepository;
    private final ProductBatchRepository productBatchRepository;
    private final InvoiceRepository invoiceRepository;

    public DashboardService(PermissionService permissionService,
                            ProductRepository productRepository,
                            SalesOrderRepository salesOrderRepository,
                            PurchaseOrderRepository purchaseOrderRepository,
                            SalesOrderItemRepository salesOrderItemRepository,
                            TenderRepository tenderRepository,
                            ProductBatchRepository productBatchRepository,
                            InvoiceRepository invoiceRepository) {
        this.permissionService = permissionService;
        this.productRepository = productRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.salesOrderItemRepository = salesOrderItemRepository;
        this.tenderRepository = tenderRepository;
        this.productBatchRepository = productBatchRepository;
        this.invoiceRepository = invoiceRepository;
    }

    @Transactional(readOnly = true)
    public DashboardStatsDto getStats(Authentication auth) {
        boolean canSales = permissionService.canView(auth, "SALES_ORDERS");
        boolean canPurchases = permissionService.canView(auth, "PURCHASE_ORDERS");
        boolean canProducts = permissionService.canView(auth, "PRODUCTS");
        boolean canTenders = permissionService.canView(auth, "TENDERS");
        boolean canInvoices = permissionService.canView(auth, "INVOICES");

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
        // Every figure on this dashboard spans many orders, so each amount is converted to the company's
        // base currency first - see MoneyConverter for why raw sums are wrong here.
        for (SalesOrder o : salesOrders) {
            if (isCancelled(o.getStatus())) continue;
            YearMonth ym = monthOf(o.getOrderDate());
            if (ym != null) revenueByMonth.merge(ym, baseOf(o), BigDecimal::add);
        }
        for (PurchaseOrder o : purchaseOrders) {
            if (isCancelled(o.getStatus())) continue;
            YearMonth ym = monthOf(o.getOrderDate());
            if (ym != null) spendByMonth.merge(ym, baseOf(o), BigDecimal::add);
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
        Money sales = canSales ? money(salesOrders, SalesOrder::getOrderDate, DashboardService::baseOf,
                o -> o.getStatus() != null ? o.getStatus().name() : null, thisMonth, lastMonth) : null;
        Money purchases = canPurchases ? money(purchaseOrders, PurchaseOrder::getOrderDate, DashboardService::baseOf,
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
            // The widget shows the worst few and links to the filtered product list for the rest, so
            // there is no reason to ship every low-stock row - the count above stays the true total.
            productsBlock = new ProductsBlock(products.size(), lowStock.size(),
                    lowStock.stream().limit(LOW_STOCK_LIMIT).toList());
        }

        // ---- expiry block (lots already expired or expiring within the horizon) ------------------
        ExpiryBlock expiryBlock = null;
        if (canProducts) {
            LocalDate today = LocalDate.now();
            LocalDate cutoff = today.plusDays(EXPIRY_HORIZON_DAYS);
            List<ExpiringLotRow> rows = productBatchRepository.findExpiringByCutoff(cutoff).stream()
                    .map(b -> new ExpiringLotRow(
                            b.getProduct() != null ? b.getProduct().getId() : null,
                            b.getProduct() != null ? b.getProduct().getName() : null,
                            b.getLotNumber(),
                            b.getWarehouse() != null ? b.getWarehouse().getName() : null,
                            nz(b.getQuantity()),
                            b.getExpiryDate()))
                    .toList();
            int expiredCount = (int) rows.stream()
                    .filter(r -> r.expiryDate() != null && r.expiryDate().isBefore(today))
                    .count();
            expiryBlock = new ExpiryBlock(expiredCount, rows);
        }

        // ---- tenders block ----------------------------------------------------------------------
        TendersBlock tendersBlock = null;
        if (canTenders) {
            int activeTenders = (int) tenders.stream().filter(t -> isActive(t.getStatus())).count();
            BigDecimal totalValue = tenders.stream()
                    .map(DashboardService::baseOf)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            List<TenderRow> latest = tenders.stream()
                    .sorted(Comparator.comparing(Tender::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(LATEST_TENDERS)
                    .map(t -> new TenderRow(
                            t.getId(),
                            t.getTitle(),
                            t.getStatus(),
                            tenderCustomer(t),
                            t.getEstimatedValue() != null ? baseOf(t) : null
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
                        .add(baseOf(o), 1);
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
                        .add(baseOf(item.getLineTotal(), so), nz(item.getQuantity()));
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
                        o.getOrderDate(), baseOf(o)));
            }
        }
        if (canPurchases) {
            for (PurchaseOrder o : purchaseOrders) {
                activity.add(new ActivityItem("PURCHASE", o.getId(),
                        o.getManufacturer() != null ? o.getManufacturer().getName() : o.getOrderNumber(),
                        o.getStatus() != null ? o.getStatus().name() : null,
                        o.getOrderDate(), baseOf(o)));
            }
        }
        if (canTenders) {
            for (Tender t : tenders) {
                activity.add(new ActivityItem("TENDER", t.getId(), t.getTitle(), t.getStatus(),
                        t.getPublishedAt(),
                        baseOf(t)));
            }
        }
        activity = activity.stream()
                .sorted(Comparator.comparing(ActivityItem::date, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(ACTIVITY_LIMIT)
                .toList();

        // ---- fulfilment (operational, money-free; powers the warehouse dashboard) ----------------
        Fulfilment fulfilment = null;
        if (canSales || canPurchases) {
            List<FulfilmentOrder> salesToShip = canSales ? salesOrders.stream()
                    .filter(o -> isOpenForFulfilment(o.getStatus()))
                    .sorted(Comparator.comparing(SalesOrder::getOrderDate, Comparator.nullsLast(Comparator.naturalOrder())))
                    .limit(FULFILMENT_LIMIT)
                    .map(o -> new FulfilmentOrder(
                            o.getId(),
                            o.getOrderNumber(),
                            o.getClient() != null ? o.getClient().getName() : null,
                            o.getStatus() != null ? o.getStatus().name() : null,
                            o.getOrderDate(),
                            null))
                    .toList() : List.of();

            List<FulfilmentOrder> purchasesIncoming = canPurchases ? purchaseOrders.stream()
                    .filter(o -> isOpenForFulfilment(o.getStatus()))
                    // Soonest expected first; orders without a date sink to the bottom.
                    .sorted(Comparator.comparing(PurchaseOrder::getExpectedDeliveryDate, Comparator.nullsLast(Comparator.naturalOrder())))
                    .limit(FULFILMENT_LIMIT)
                    .map(o -> new FulfilmentOrder(
                            o.getId(),
                            o.getOrderNumber(),
                            o.getManufacturer() != null ? o.getManufacturer().getName() : null,
                            o.getStatus() != null ? o.getStatus().name() : null,
                            o.getOrderDate(),
                            o.getExpectedDeliveryDate()))
                    .toList() : List.of();

            fulfilment = new Fulfilment(salesToShip, purchasesIncoming, salesToShip.size(), purchasesIncoming.size());
        }

        // ---- receivables (outstanding invoices) + collected (cash received) ---------------------
        Receivables receivables = null;
        Money collected = null;
        if (canInvoices) {
            List<Invoice> invoices = invoiceRepository.findAll();
            receivables = receivables(invoices, LocalDate.now());
            collected = collected(invoices, thisMonth, lastMonth);
        }

        return new DashboardStatsDto(sales, purchases, productsBlock, tendersBlock, monthly, topClients, topProducts, activity, fulfilment, expiryBlock, receivables, collected);
    }

    /**
     * Cash collected from invoice payments this vs last month, keyed on the recorded paid date rather
     * than the order date, so money received for older orders still counts in a month with few new sales.
     * Each paid invoice contributes its principal (total less any prepayment already applied), so a
     * prepayment and the final invoice that nets it never double-count the same money.
     */
    private Money collected(List<Invoice> invoices, YearMonth thisMonth, YearMonth lastMonth) {
        BigDecimal thisTotal = BigDecimal.ZERO;
        BigDecimal lastTotal = BigDecimal.ZERO;
        int thisCount = 0;
        int paidCount = 0;
        for (Invoice inv : invoices) {
            if (inv.getStatus() != InvoicePaymentStatus.PAID || inv.getPaidDate() == null) continue;
            paidCount++;
            BigDecimal principal = basePrincipal(inv);
            YearMonth ym = YearMonth.from(inv.getPaidDate());
            if (ym.equals(thisMonth)) {
                thisTotal = thisTotal.add(principal);
                thisCount++;
            } else if (ym.equals(lastMonth)) {
                lastTotal = lastTotal.add(principal);
            }
        }
        return new Money(thisTotal, lastTotal, thisCount, paidCount);
    }

    /**
     * Outstanding-invoice figures as of {@code today}: totals for everything unpaid, the overdue subset
     * and the penalty accruing on it, plus the five largest overdue balances. Penalty and amount due are
     * charged on the outstanding principal (invoice total less any prepayment already applied), matching
     * how {@code InvoiceService} derives them.
     */
    private Receivables receivables(List<Invoice> invoices, LocalDate today) {
        BigDecimal unpaidTotal = BigDecimal.ZERO;
        BigDecimal overdueTotal = BigDecimal.ZERO;
        BigDecimal penaltyTotal = BigDecimal.ZERO;
        int unpaidCount = 0;
        int overdueCount = 0;
        List<OverdueRow> overdue = new ArrayList<>();

        for (Invoice inv : invoices) {
            if (inv.getStatus() != InvoicePaymentStatus.UNPAID) continue;
            BigDecimal principal = basePrincipal(inv);
            unpaidCount++;
            unpaidTotal = unpaidTotal.add(principal);

            PenaltyCalculator.Result penalty = PenaltyCalculator.calculate(principal, inv.getPenaltyPercent(),
                    inv.getPenaltyPeriod(), inv.getDueDate(), today);
            if (!penalty.overdue()) continue;

            overdueCount++;
            overdueTotal = overdueTotal.add(principal);
            penaltyTotal = penaltyTotal.add(penalty.penaltyAmount());
            long daysOverdue = inv.getDueDate() != null ? ChronoUnit.DAYS.between(inv.getDueDate(), today) : 0;
            overdue.add(new OverdueRow(
                    inv.getId(),
                    inv.getInvoiceNumber(),
                    inv.getSalesOrder() != null ? inv.getSalesOrder().getId() : null,
                    inv.getSalesOrder() != null ? inv.getSalesOrder().getOrderNumber() : null,
                    inv.getClientName(),
                    inv.getDueDate(),
                    (int) daysOverdue,
                    inv.getCurrency(),
                    principal.add(penalty.penaltyAmount())));
        }

        List<OverdueRow> topOverdue = overdue.stream()
                .sorted(Comparator.comparing(OverdueRow::amountDue).reversed())
                .limit(TOP_LIMIT)
                .toList();
        return new Receivables(unpaidCount, unpaidTotal, overdueCount, overdueTotal, penaltyTotal, topOverdue,
                aging(overdue));
    }

    /**
     * Splits every overdue row into the three aging bands, so the dashboard can chart how much of the
     * debt is merely late versus long gone. Buckets are always all three, zeros included, to keep the
     * chart's segments stable between refreshes.
     */
    private static List<AgingBucket> aging(List<OverdueRow> overdue) {
        int[] counts = new int[3];
        BigDecimal[] totals = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (OverdueRow row : overdue) {
            int i = row.daysOverdue() <= 30 ? 0 : row.daysOverdue() <= 60 ? 1 : 2;
            counts[i]++;
            totals[i] = totals[i].add(row.amountDue());
        }
        return List.of(
                new AgingBucket("D1_30", counts[0], totals[0]),
                new AgingBucket("D31_60", counts[1], totals[1]),
                new AgingBucket("D60_PLUS", counts[2], totals[2]));
    }

    private static boolean isOpenForFulfilment(OrderStatus status) {
        return status != null && OPEN_FOR_FULFILMENT.contains(status);
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
        // The tender's requester is its linked client.
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

    // --- Base-currency conversion ----------------------------------------------------------------
    // Every figure on this dashboard aggregates across many records, which may each be in a different
    // currency, so amounts are converted with the rate snapshotted on their own transaction before being
    // summed. See MoneyConverter.

    private static BigDecimal baseOf(SalesOrder order) {
        return MoneyConverter.toBase(order.getTotalAmount(), order.getExchangeRate());
    }

    private static BigDecimal baseOf(PurchaseOrder order) {
        return MoneyConverter.toBase(order.getTotalAmount(), order.getExchangeRate());
    }

    private static BigDecimal baseOf(Tender tender) {
        return MoneyConverter.toBase(tender.getEstimatedValue(), tender.getExchangeRate());
    }

    /** A line amount converted with its parent order's rate. */
    private static BigDecimal baseOf(BigDecimal lineAmount, SalesOrder order) {
        return MoneyConverter.toBase(lineAmount, order != null ? order.getExchangeRate() : null);
    }

    /**
     * An invoice's outstanding principal (total less any prepayment already applied) in base currency.
     * Invoices carry a currency but no rate of their own, so the rate comes from the sales order they
     * were issued for.
     */
    private static BigDecimal basePrincipal(Invoice invoice) {
        BigDecimal principal = nz(invoice.getTotalAmount()).subtract(nz(invoice.getAppliedPrepaymentAmount()));
        SalesOrder order = invoice.getSalesOrder();
        return MoneyConverter.toBase(principal, order != null ? order.getExchangeRate() : null);
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
