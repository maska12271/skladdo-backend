package com.example.tenderapp.service;

import com.example.tenderapp.dto.OrderDetailsDto;
import com.example.tenderapp.dto.OrderDetailsDto.*;
import com.example.tenderapp.exception.ResourceNotFoundException;
import com.example.tenderapp.model.*;
import com.example.tenderapp.repository.OrderStatusChangeRepository;
import com.example.tenderapp.repository.PurchaseOrderItemRepository;
import com.example.tenderapp.repository.PurchaseOrderRepository;
import com.example.tenderapp.repository.SalesOrderRepository;
import com.example.tenderapp.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the {@link OrderDetailsDto} for the sales/purchase order detail pages: line items, money
 * and quantity totals, the audit trail and the recorded status timeline. Read-only and transactional
 * so lazy item collections can be traversed with open-in-view disabled.
 */
@Service
public class OrderAnalyticsService {

    private final SalesOrderRepository salesOrderRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final OrderStatusChangeRepository statusChangeRepository;
    private final UserRepository userRepository;

    public OrderAnalyticsService(SalesOrderRepository salesOrderRepository,
                                 PurchaseOrderRepository purchaseOrderRepository,
                                 PurchaseOrderItemRepository purchaseOrderItemRepository,
                                 OrderStatusChangeRepository statusChangeRepository,
                                 UserRepository userRepository) {
        this.salesOrderRepository = salesOrderRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
        this.statusChangeRepository = statusChangeRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public OrderDetailsDto getSalesOrderDetails(Long id) {
        SalesOrder order = salesOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sales order not found with id: " + id));

        Map<Long, BigDecimal> avgCostCache = new HashMap<>();
        List<Line> lines = new ArrayList<>();
        for (SalesOrderItem item : order.getItems()) {
            Product product = item.getProduct();
            BigDecimal avgCost = product != null
                    ? avgCostCache.computeIfAbsent(product.getId(), this::weightedAvgPurchaseCost)
                    : BigDecimal.ZERO;
            lines.add(new Line(
                    product != null ? product.getId() : null,
                    product != null ? product.getName() : null,
                    product != null ? product.getSku() : null,
                    nz(item.getQuantity()),
                    nz(item.getUnitPrice()),
                    nz(item.getLineTotal()),
                    avgCost
            ));
        }

        Totals totals = salesTotals(order, lines);
        return new OrderDetailsDto(
                order.getId(), "SALES", order.getOrderNumber(),
                order.getStatus() != null ? order.getStatus().name() : null,
                order.getClient() != null ? order.getClient().getId() : null,
                order.getClient() != null ? order.getClient().getName() : null,
                "Client",
                order.getOrderDate(), order.getClosingDate(), null,
                order.getDeliveryAddress(), order.getNotes(),
                lines, totals,
                audit(order.getCreatedById(), order.getCreatedAt(), order.getUpdatedById(), order.getUpdatedAt()),
                statusHistory(OrderType.SALES, order.getId())
        );
    }

    @Transactional(readOnly = true)
    public OrderDetailsDto getPurchaseOrderDetails(Long id) {
        PurchaseOrder order = purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase order not found with id: " + id));

        List<Line> lines = new ArrayList<>();
        for (PurchaseOrderItem item : order.getItems()) {
            Product product = item.getProduct();
            lines.add(new Line(
                    product != null ? product.getId() : null,
                    product != null ? product.getName() : null,
                    product != null ? product.getSku() : null,
                    nz(item.getQuantity()),
                    nz(item.getUnitPrice()),
                    nz(item.getLineTotal()),
                    null // a purchase line IS the cost, so no separate estimate
            ));
        }

        Totals totals = purchaseTotals(order, lines);
        return new OrderDetailsDto(
                order.getId(), "PURCHASE", order.getOrderNumber(),
                order.getStatus() != null ? order.getStatus().name() : null,
                order.getManufacturer() != null ? order.getManufacturer().getId() : null,
                order.getManufacturer() != null ? order.getManufacturer().getName() : null,
                "Manufacturer",
                order.getOrderDate(), order.getClosingDate(), order.getExpectedDeliveryDate(),
                order.getDeliveryAddress(), order.getNotes(),
                lines, totals,
                audit(order.getCreatedById(), order.getCreatedAt(), order.getUpdatedById(), order.getUpdatedAt()),
                statusHistory(OrderType.PURCHASE, order.getId())
        );
    }

    // ---------------------------------------------------------------------------------------------

    private Totals salesTotals(SalesOrder order, List<Line> lines) {
        long units = 0;
        BigDecimal estCost = BigDecimal.ZERO;
        Set<Long> products = new java.util.HashSet<>();
        for (Line l : lines) {
            units += l.quantity();
            if (l.productId() != null) products.add(l.productId());
            if (l.estUnitCost() != null) estCost = estCost.add(l.estUnitCost().multiply(BigDecimal.valueOf(l.quantity())));
        }
        BigDecimal subtotal = nz(order.getSubtotalAmount());
        return new Totals(
                subtotal,
                nz(order.getDeliveryPrice()),
                nz(order.getTotalAmount()),
                units,
                products.size(),
                perUnit(nz(order.getDeliveryPrice()), units),
                estCost,
                subtotal.subtract(estCost)
        );
    }

    private Totals purchaseTotals(PurchaseOrder order, List<Line> lines) {
        long units = 0;
        Set<Long> products = new java.util.HashSet<>();
        for (Line l : lines) {
            units += l.quantity();
            if (l.productId() != null) products.add(l.productId());
        }
        return new Totals(
                nz(order.getSubtotalAmount()),
                nz(order.getDeliveryPrice()),
                nz(order.getTotalAmount()),
                units,
                products.size(),
                perUnit(nz(order.getDeliveryPrice()), units),
                null,
                null
        );
    }

    /** Weighted-average unit purchase cost for a product across all non-cancelled purchase orders. */
    private BigDecimal weightedAvgPurchaseCost(Long productId) {
        long units = 0;
        BigDecimal cost = BigDecimal.ZERO;
        for (PurchaseOrderItem item : purchaseOrderItemRepository.findByProductId(productId)) {
            PurchaseOrder po = item.getPurchaseOrder();
            if (po != null && po.getStatus() == OrderStatus.CANCELLED) continue;
            units += nz(item.getQuantity());
            cost = cost.add(nz(item.getLineTotal()));
        }
        return units > 0 ? cost.divide(BigDecimal.valueOf(units), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }

    private List<StatusEvent> statusHistory(OrderType type, Long orderId) {
        List<StatusEvent> events = new ArrayList<>();
        for (OrderStatusChange c : statusChangeRepository.findByOrderTypeAndOrderIdOrderByChangedAtAscIdAsc(type, orderId)) {
            events.add(new StatusEvent(
                    c.getFromStatus() != null ? c.getFromStatus().name() : null,
                    c.getToStatus() != null ? c.getToStatus().name() : null,
                    c.getChangedAt(),
                    actor(c.getChangedById())
            ));
        }
        return events;
    }

    private AuditInfo audit(Long createdById, java.time.Instant createdAt, Long updatedById, java.time.Instant updatedAt) {
        return new AuditInfo(actor(createdById), createdAt, actor(updatedById), updatedAt);
    }

    private Actor actor(Long userId) {
        if (userId == null) return null;
        return userRepository.findById(userId)
                .map(u -> new Actor(u.getId(), u.getFullName() != null ? u.getFullName() : u.getEmail()))
                .orElse(null);
    }

    private static BigDecimal perUnit(BigDecimal amount, long units) {
        return units > 0 ? amount.divide(BigDecimal.valueOf(units), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }

    private static int nz(Integer value) {
        return value != null ? value : 0;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
