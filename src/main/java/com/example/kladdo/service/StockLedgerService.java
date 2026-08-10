package com.example.kladdo.service;

import com.example.kladdo.dto.StockMovementDto;
import com.example.kladdo.model.InventoryAdjustment;
import com.example.kladdo.model.OrderStatus;
import com.example.kladdo.model.OrderStatusChange;
import com.example.kladdo.model.OrderType;
import com.example.kladdo.model.StockTransfer;
import com.example.kladdo.model.User;
import com.example.kladdo.repository.InventoryAdjustmentRepository;
import com.example.kladdo.repository.OrderStatusChangeRepository;
import com.example.kladdo.repository.PurchaseOrderRepository;
import com.example.kladdo.repository.SalesOrderRepository;
import com.example.kladdo.repository.StockTransferRepository;
import com.example.kladdo.repository.UserRepository;
import com.example.kladdo.repository.WarehouseStockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a warehouse's stock ledger for one product: every movement in and out, newest first, with the
 * on-hand balance after each one.
 *
 * <p>There is no stock-movement table. Every change to {@code WarehouseStock} goes through the single
 * choke point {@code WarehouseService.adjustWarehouseStock}, and each of its callers already leaves a
 * durable record, so the ledger is <em>derived</em> by unioning those records:</p>
 * <ul>
 *   <li>{@link InventoryAdjustment} - manual stock-takes.</li>
 *   <li>{@link StockTransfer} - movements between warehouses.</li>
 *   <li>{@link OrderStatusChange} - replayed per order: stock moves whenever an order crosses into or out
 *       of a stock-affecting status, exactly as the order services do it.</li>
 * </ul>
 *
 * <p><strong>Accuracy.</strong> Balances are computed backwards from today's {@code WarehouseStock}, so the
 * newest line always agrees with current stock. The order replay uses each order's <em>current</em> line
 * quantities, so if an order's lines were edited after it shipped the older balances can drift - the recent
 * end of the ledger, which is what the question "why is my stock N?" is about, stays exact.</p>
 */
@Service
public class StockLedgerService {

    /** Mirrors the rule both order services apply: these statuses are the ones that hold stock. */
    static boolean isStockAffecting(OrderStatus status) {
        return status == OrderStatus.SHIPPED || status == OrderStatus.CLOSED;
    }

    private final WarehouseService warehouseService;
    private final WarehouseStockRepository warehouseStockRepository;
    private final InventoryAdjustmentRepository adjustmentRepository;
    private final StockTransferRepository transferRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final OrderStatusChangeRepository statusChangeRepository;
    private final UserRepository userRepository;

    public StockLedgerService(WarehouseService warehouseService,
                              WarehouseStockRepository warehouseStockRepository,
                              InventoryAdjustmentRepository adjustmentRepository,
                              StockTransferRepository transferRepository,
                              SalesOrderRepository salesOrderRepository,
                              PurchaseOrderRepository purchaseOrderRepository,
                              OrderStatusChangeRepository statusChangeRepository,
                              UserRepository userRepository) {
        this.warehouseService = warehouseService;
        this.warehouseStockRepository = warehouseStockRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.transferRepository = transferRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.statusChangeRepository = statusChangeRepository;
        this.userRepository = userRepository;
    }

    /** A movement before its balance is known - the raw union, still in chronological order. */
    record RawMovement(Instant timestamp, StockMovementDto.Kind kind, int quantityChange,
                       String reference, String note, String sourceType, Long sourceId, Long actorUserId) {
    }

    /** The ledger for {@code productId} in {@code warehouseId}, newest first. */
    @Transactional(readOnly = true)
    public List<StockMovementDto> movements(Long warehouseId, Long productId) {
        warehouseService.requireAccess(warehouseId); // 403s for staff not assigned to this warehouse

        List<RawMovement> raw = new ArrayList<>();
        raw.addAll(adjustments(warehouseId, productId));
        raw.addAll(transfers(warehouseId, productId));
        raw.addAll(orderMovements(OrderType.SALES, warehouseId, productId));
        raw.addAll(orderMovements(OrderType.PURCHASE, warehouseId, productId));

        int currentStock = warehouseStockRepository.findByWarehouseIdAndProductId(warehouseId, productId)
                .map(stock -> stock.getQuantity())
                .orElse(0);

        return withBalances(raw, currentStock, actorNames(raw));
    }

    /**
     * Turns the raw union into the response: sorts oldest-first, walks the balance backwards from current
     * stock, and returns newest-first. Pure and package-private so the balance maths can be unit-tested.
     */
    static List<StockMovementDto> withBalances(List<RawMovement> raw, int currentStock, Map<Long, String> actorNames) {
        List<RawMovement> ordered = new ArrayList<>(raw);
        ordered.sort(Comparator.comparing(RawMovement::timestamp,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(RawMovement::sourceId, Comparator.nullsFirst(Comparator.naturalOrder())));

        // Walk from the newest backwards: the newest line's balance is today's stock, and each earlier
        // line's balance is the next one's minus that next movement.
        StockMovementDto[] result = new StockMovementDto[ordered.size()];
        int running = currentStock;
        for (int i = ordered.size() - 1; i >= 0; i--) {
            RawMovement m = ordered.get(i);
            result[i] = new StockMovementDto(
                    m.timestamp(), m.kind(), m.quantityChange(), running,
                    m.reference(), m.note(), m.sourceType(), m.sourceId(),
                    m.actorUserId(), m.actorUserId() == null ? null : actorNames.get(m.actorUserId()));
            running -= m.quantityChange();
        }

        List<StockMovementDto> newestFirst = new ArrayList<>(List.of(result));
        java.util.Collections.reverse(newestFirst);
        return newestFirst;
    }

    // --- Sources ----------------------------------------------------------------------------------

    private List<RawMovement> adjustments(Long warehouseId, Long productId) {
        List<RawMovement> out = new ArrayList<>();
        for (InventoryAdjustment a : adjustmentRepository.findByProductIdOrderByCreatedAtDescIdDesc(productId)) {
            if (a.getWarehouse() == null || !warehouseId.equals(a.getWarehouse().getId())) {
                continue;
            }
            int change = a.getQuantityChange() == null ? 0 : a.getQuantityChange();
            out.add(new RawMovement(a.getCreatedAt(), StockMovementDto.Kind.ADJUSTMENT, change,
                    null, a.getNote(), "INVENTORY_ADJUSTMENT", a.getId(), a.getCreatedById()));
        }
        return out;
    }

    private List<RawMovement> transfers(Long warehouseId, Long productId) {
        List<RawMovement> out = new ArrayList<>();
        for (StockTransfer t : transferRepository.findByProductIdWithWarehouses(productId)) {
            int qty = t.getQuantity() == null ? 0 : t.getQuantity();
            boolean isSource = t.getFromWarehouse() != null && warehouseId.equals(t.getFromWarehouse().getId());
            boolean isTarget = t.getToWarehouse() != null && warehouseId.equals(t.getToWarehouse().getId());
            if (isSource) {
                out.add(new RawMovement(t.getCreatedAt(), StockMovementDto.Kind.TRANSFER_OUT, -qty,
                        t.getToWarehouse() != null ? t.getToWarehouse().getName() : null,
                        t.getNote(), "STOCK_TRANSFER", t.getId(), t.getCreatedById()));
            }
            if (isTarget) {
                out.add(new RawMovement(t.getCreatedAt(), StockMovementDto.Kind.TRANSFER_IN, qty,
                        t.getFromWarehouse() != null ? t.getFromWarehouse().getName() : null,
                        t.getNote(), "STOCK_TRANSFER", t.getId(), t.getCreatedById()));
            }
        }
        return out;
    }

    /**
     * Replays every relevant order's status history. Stock moves only when an order crosses the
     * stock-affecting boundary, so a PENDING -> CONFIRMED change contributes nothing while
     * CONFIRMED -> SHIPPED moves the whole line quantity.
     */
    private List<RawMovement> orderMovements(OrderType orderType, Long warehouseId, Long productId) {
        List<Object[]> rows = orderType == OrderType.SALES
                ? salesOrderRepository.findOrderedQuantitiesForProduct(warehouseId, productId)
                : purchaseOrderRepository.findOrderedQuantitiesForProduct(warehouseId, productId);
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<Long, String> orderNumbers = new HashMap<>();
        Map<Long, Integer> quantities = new HashMap<>();
        for (Object[] row : rows) {
            Long orderId = ((Number) row[0]).longValue();
            orderNumbers.put(orderId, (String) row[1]);
            quantities.put(orderId, ((Number) row[2]).intValue());
        }

        List<RawMovement> out = new ArrayList<>();
        Map<Long, Boolean> holdingStock = new HashMap<>();
        // One query for every order's history, oldest first, so the fold below sees them in order.
        for (OrderStatusChange change : statusChangeRepository
                .findByOrderTypeAndOrderIdInOrderByChangedAtAscIdAsc(orderType, orderNumbers.keySet())) {
            Long orderId = change.getOrderId();
            boolean was = holdingStock.getOrDefault(orderId, false);
            boolean now = isStockAffecting(change.getToStatus());
            if (was == now) {
                continue;
            }
            holdingStock.put(orderId, now);

            int qty = quantities.getOrDefault(orderId, 0);
            boolean sales = orderType == OrderType.SALES;
            // Sales issue stock when they start holding it; purchases receive it.
            int delta = now ? (sales ? -qty : qty) : (sales ? qty : -qty);
            StockMovementDto.Kind kind = sales
                    ? (now ? StockMovementDto.Kind.SALES_OUT : StockMovementDto.Kind.SALES_IN)
                    : (now ? StockMovementDto.Kind.PURCHASE_IN : StockMovementDto.Kind.PURCHASE_OUT);

            out.add(new RawMovement(change.getChangedAt(), kind, delta,
                    orderNumbers.get(orderId), null,
                    sales ? "SALES_ORDER" : "PURCHASE_ORDER", orderId, change.getChangedById()));
        }
        return out;
    }

    /** Resolves the actor ids in one query so the ledger does not do a user lookup per row. */
    private Map<Long, String> actorNames(List<RawMovement> raw) {
        List<Long> ids = raw.stream()
                .map(RawMovement::actorUserId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (User user : userRepository.findAllById(ids)) {
            names.put(user.getId(), user.getFullName() != null && !user.getFullName().isBlank()
                    ? user.getFullName() : user.getEmail());
        }
        return names;
    }
}
