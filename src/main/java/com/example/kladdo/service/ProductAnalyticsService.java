package com.example.kladdo.service;

import com.example.kladdo.dto.ProductDetailsDto;
import com.example.kladdo.dto.ProductDetailsDto.*;
import com.example.kladdo.model.*;
import com.example.kladdo.repository.ProductRepository;
import com.example.kladdo.repository.PurchaseOrderItemRepository;
import com.example.kladdo.repository.SalesOrderItemRepository;
import com.example.kladdo.repository.UserRepository;
import com.example.kladdo.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * Builds the aggregated {@link ProductDetailsDto} for the product detail page from the order
 * lines a product appears in. Read-only and transactional so lazy associations
 * (order -> client/manufacturer) can be traversed with open-in-view disabled.
 */
@Service
public class ProductAnalyticsService {

    private final ProductRepository productRepository;
    private final SalesOrderItemRepository salesOrderItemRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final UserRepository userRepository;

    public ProductAnalyticsService(ProductRepository productRepository,
                                   SalesOrderItemRepository salesOrderItemRepository,
                                   PurchaseOrderItemRepository purchaseOrderItemRepository,
                                   UserRepository userRepository) {
        this.productRepository = productRepository;
        this.salesOrderItemRepository = salesOrderItemRepository;
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public ProductDetailsDto getDetails(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + productId));

        List<SalesOrderItem> salesItems = salesOrderItemRepository.findByProductId(productId);
        List<PurchaseOrderItem> purchaseItems = purchaseOrderItemRepository.findByProductId(productId);

        // ---- order line lists -------------------------------------------------------------------
        List<OrderLine> salesOrders = new ArrayList<>();
        long totalUnitsSold = 0;
        BigDecimal totalRevenue = BigDecimal.ZERO;
        Set<Long> salesOrderIds = new HashSet<>();
        for (SalesOrderItem item : salesItems) {
            SalesOrder order = item.getSalesOrder();
            int qty = nz(item.getQuantity());
            // A product's history spans orders in different currencies, so every amount here - the
            // running totals and the rows - is converted to base with its own order's rate.
            BigDecimal line = MoneyConverter.toBase(item.getLineTotal(), rateOf(order));
            // Cancelled orders are still listed in the table below, but never counted as revenue.
            if (!isCancelled(order)) {
                totalUnitsSold += qty;
                totalRevenue = totalRevenue.add(line);
                if (order != null) salesOrderIds.add(order.getId());
            }
            salesOrders.add(new OrderLine(
                    order != null ? order.getId() : null,
                    order != null ? order.getOrderNumber() : null,
                    order != null ? order.getOrderDate() : null,
                    order != null && order.getStatus() != null ? order.getStatus().name() : null,
                    order != null && order.getClient() != null ? order.getClient().getName() : null,
                    qty,
                    MoneyConverter.toBase(item.getUnitPrice(), rateOf(order)),
                    line
            ));
        }

        List<OrderLine> purchaseOrders = new ArrayList<>();
        long totalUnitsPurchased = 0;
        BigDecimal totalPurchaseCost = BigDecimal.ZERO;
        Set<Long> purchaseOrderIds = new HashSet<>();
        for (PurchaseOrderItem item : purchaseItems) {
            PurchaseOrder order = item.getPurchaseOrder();
            int qty = nz(item.getQuantity());
            BigDecimal line = MoneyConverter.toBase(item.getLineTotal(), rateOf(order));
            // Cancelled orders are still listed in the table below, but never counted as cost.
            if (!isCancelled(order)) {
                totalUnitsPurchased += qty;
                totalPurchaseCost = totalPurchaseCost.add(line);
                if (order != null) purchaseOrderIds.add(order.getId());
            }
            purchaseOrders.add(new OrderLine(
                    order != null ? order.getId() : null,
                    order != null ? order.getOrderNumber() : null,
                    order != null ? order.getOrderDate() : null,
                    order != null && order.getStatus() != null ? order.getStatus().name() : null,
                    order != null && order.getManufacturer() != null ? order.getManufacturer().getName() : null,
                    qty,
                    MoneyConverter.toBase(item.getUnitPrice(), rateOf(order)),
                    line
            ));
        }

        salesOrders.sort(byDateDesc());
        purchaseOrders.sort(byDateDesc());

        // ---- summary ----------------------------------------------------------------------------
        BigDecimal weightedAvgCost = totalUnitsPurchased > 0
                ? totalPurchaseCost.divide(BigDecimal.valueOf(totalUnitsPurchased), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        // Gross profit on what was sold, valued at the weighted-average purchase cost.
        BigDecimal grossProfit = totalRevenue.subtract(
                weightedAvgCost.multiply(BigDecimal.valueOf(totalUnitsSold)));

        Summary summary = new Summary(
                totalUnitsSold,
                totalRevenue,
                salesOrderIds.size(),
                totalUnitsPurchased,
                totalPurchaseCost,
                purchaseOrderIds.size(),
                weightedAvgCost,
                grossProfit
        );

        // ---- monthly time series ----------------------------------------------------------------
        Map<YearMonth, long[]> units = new TreeMap<>();      // [unitsSold, unitsPurchased]
        Map<YearMonth, BigDecimal> revenue = new TreeMap<>();
        for (SalesOrderItem item : salesItems) {
            if (isCancelled(item.getSalesOrder())) continue;
            YearMonth ym = monthOf(item.getSalesOrder() != null ? item.getSalesOrder().getOrderDate() : null);
            if (ym == null) continue;
            units.computeIfAbsent(ym, k -> new long[2])[0] += nz(item.getQuantity());
            revenue.merge(ym, MoneyConverter.toBase(item.getLineTotal(), rateOf(item.getSalesOrder())), BigDecimal::add);
        }
        for (PurchaseOrderItem item : purchaseItems) {
            if (isCancelled(item.getPurchaseOrder())) continue;
            YearMonth ym = monthOf(item.getPurchaseOrder() != null ? item.getPurchaseOrder().getOrderDate() : null);
            if (ym == null) continue;
            units.computeIfAbsent(ym, k -> new long[2])[1] += nz(item.getQuantity());
        }
        List<MonthlyPoint> monthly = new ArrayList<>();
        for (Map.Entry<YearMonth, long[]> entry : units.entrySet()) {
            YearMonth ym = entry.getKey();
            monthly.add(new MonthlyPoint(
                    ym.toString(),
                    entry.getValue()[0],
                    revenue.getOrDefault(ym, BigDecimal.ZERO),
                    entry.getValue()[1]
            ));
        }

        // ---- audit --------------------------------------------------------------------------------
        AuditInfo audit = new AuditInfo(
                actor(product.getCreatedById()),
                product.getCreatedAt(),
                actor(product.getUpdatedById()),
                product.getUpdatedAt()
        );

        return new ProductDetailsDto(audit, summary, monthly, salesOrders, purchaseOrders);
    }

    private Actor actor(Long userId) {
        if (userId == null) return null;
        return userRepository.findById(userId)
                .map(u -> new Actor(u.getId(), u.getFullName() != null ? u.getFullName() : u.getEmail()))
                .orElse(null);
    }

    private static Comparator<OrderLine> byDateDesc() {
        return Comparator.comparing(OrderLine::orderDate, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private static YearMonth monthOf(LocalDate date) {
        return date != null ? YearMonth.from(date) : null;
    }

    private static boolean isCancelled(SalesOrder order) {
        return order != null && order.getStatus() == OrderStatus.CANCELLED;
    }

    private static boolean isCancelled(PurchaseOrder order) {
        return order != null && order.getStatus() == OrderStatus.CANCELLED;
    }

    private static BigDecimal rateOf(SalesOrder order) {
        return order != null ? order.getExchangeRate() : null;
    }

    private static BigDecimal rateOf(PurchaseOrder order) {
        return order != null ? order.getExchangeRate() : null;
    }

    private static int nz(Integer value) {
        return value != null ? value : 0;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
