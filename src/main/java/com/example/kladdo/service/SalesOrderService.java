package com.example.kladdo.service;

import com.example.kladdo.dto.CreateSalesOrderRequest;
import com.example.kladdo.dto.SalesOrderItemRequest;
import com.example.kladdo.dto.UpdateSalesOrderRequest;
import com.example.kladdo.exception.BadRequestException;
import com.example.kladdo.exception.ResourceNotFoundException;
import com.example.kladdo.model.Client;
import com.example.kladdo.model.OrderStatus;
import com.example.kladdo.model.OrderStatusChange;
import com.example.kladdo.model.OrderType;
import com.example.kladdo.model.PenaltyPeriod;
import com.example.kladdo.model.Product;
import com.example.kladdo.model.SalesOrder;
import com.example.kladdo.model.SalesOrderItem;
import com.example.kladdo.model.Warehouse;
import com.example.kladdo.repository.OrderStatusChangeRepository;
import com.example.kladdo.repository.SalesOrderRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class SalesOrderService {

    private final SalesOrderRepository salesOrderRepository;
    private final ClientService clientService;
    private final ProductService productService;
    private final OrderStatusChangeRepository statusChangeRepository;
    private final WarehouseService warehouseService;
    private final ProductBatchService productBatchService;
    private final CompanySettingsService settingsService;

    public SalesOrderService(SalesOrderRepository salesOrderRepository,
                             ClientService clientService,
                             ProductService productService,
                             OrderStatusChangeRepository statusChangeRepository,
                             WarehouseService warehouseService,
                             ProductBatchService productBatchService,
                             CompanySettingsService settingsService) {
        this.salesOrderRepository = salesOrderRepository;
        this.clientService = clientService;
        this.productService = productService;
        this.statusChangeRepository = statusChangeRepository;
        this.warehouseService = warehouseService;
        this.productBatchService = productBatchService;
        this.settingsService = settingsService;
    }

    @Transactional(readOnly = true)
    public Page<SalesOrder> findAll(String search, List<Long> clientIds, List<OrderStatus> statuses,
                                    Long warehouseId, LocalDate dateFrom, LocalDate dateTo,
                                    List<Long> ids, List<Long> excludeIds, Pageable pageable) {
        List<Long> accessibleWarehouseIds = warehouseService.getAccessibleWarehouseIds();

        Specification<SalesOrder> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (search != null && !search.isBlank()) {
                String like = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("orderNumber")), like),
                        cb.like(cb.lower(root.get("client").get("name")), like)
                ));
            }
            if (clientIds != null && !clientIds.isEmpty()) {
                predicates.add(root.get("client").get("id").in(clientIds));
            }
            if (statuses != null && !statuses.isEmpty()) {
                predicates.add(root.get("status").in(statuses));
            }
            // Constrains the result to a caller-supplied id set (and/or excludes one) — used by the list
            // page's payment-status filter, which resolves matching order ids from the separate billing
            // summaries. `excludeId` covers "NOT_INVOICED" (orders with no invoice summary at all).
            if (ids != null && !ids.isEmpty()) {
                predicates.add(root.get("id").in(ids));
            }
            if (excludeIds != null && !excludeIds.isEmpty()) {
                predicates.add(cb.not(root.get("id").in(excludeIds)));
            }
            if (warehouseId != null) {
                predicates.add(cb.equal(root.get("warehouse").get("id"), warehouseId));
            }
            if (dateFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("orderDate"), dateFrom));
            }
            if (dateTo != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("orderDate"), dateTo));
            }
            if (accessibleWarehouseIds != null) {
                if (accessibleWarehouseIds.isEmpty()) {
                    predicates.add(cb.disjunction());
                } else {
                    predicates.add(root.get("warehouse").get("id").in(accessibleWarehouseIds));
                }
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<SalesOrder> page = salesOrderRepository.findAll(specification, pageable);
        // Initialize items and their lot allocations before the session closes (open-in-view=false),
        // since both are serialized with the entity.
        page.forEach(order -> order.getItems().forEach(item -> item.getBatchAllocations().size()));
        return page;
    }

    @Transactional(readOnly = true)
    public SalesOrder findById(Long id) {
        SalesOrder order = salesOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sales order not found with id: " + id));
        order.getItems().forEach(item -> item.getBatchAllocations().size());
        return order;
    }

    private static boolean isStockAffecting(OrderStatus status) {
        return status == OrderStatus.SHIPPED || status == OrderStatus.CLOSED;
    }

    @Transactional
    public SalesOrder create(CreateSalesOrderRequest request) {
        Warehouse warehouse = warehouseService.requireAccess(request.getWarehouseId());
        SalesOrder salesOrder = new SalesOrder();
        salesOrder.setWarehouse(warehouse);

        fillSalesOrderFromRequest(
                salesOrder,
                request.getClientId(),
                request.getOrderNumber(),
                request.getStatus(),
                request.getDeliveryAddress(),
                request.getNotes(),
                request.getOrderDate(),
                request.getClosingDate(),
                request.getDeliveryPrice(),
                request.getPaymentTermDays(),
                request.getPenaltyPercent(),
                request.getPenaltyPeriod(),
                request.getItems()
        );

        if (isStockAffecting(salesOrder.getStatus())) {
            decreaseStockForItems(salesOrder.getItems(), warehouse);
        }

        SalesOrder saved = salesOrderRepository.save(salesOrder);
        recordStatusChange(saved.getId(), null, saved.getStatus());
        return saved;
    }

    @Transactional
    public SalesOrder update(Long id, UpdateSalesOrderRequest request) {
        SalesOrder salesOrder = findById(id);

        OrderStatus oldStatus = salesOrder.getStatus();
        Warehouse oldWarehouse = salesOrder.getWarehouse();
        List<SalesOrderItem> oldItems = new ArrayList<>(salesOrder.getItems());

        Warehouse newWarehouse = warehouseService.requireAccess(request.getWarehouseId());
        salesOrder.setWarehouse(newWarehouse);

        fillSalesOrderFromRequest(
                salesOrder,
                request.getClientId(),
                request.getOrderNumber(),
                request.getStatus(),
                request.getDeliveryAddress(),
                request.getNotes(),
                request.getOrderDate(),
                request.getClosingDate(),
                request.getDeliveryPrice(),
                request.getPaymentTermDays(),
                request.getPenaltyPercent(),
                request.getPenaltyPeriod(),
                request.getItems()
        );

        OrderStatus newStatus = salesOrder.getStatus();

        if (isStockAffecting(oldStatus) && oldWarehouse != null) {
            increaseStockForItems(oldItems, oldWarehouse);
        }
        if (isStockAffecting(newStatus)) {
            decreaseStockForItems(salesOrder.getItems(), newWarehouse);
        }

        SalesOrder saved = salesOrderRepository.save(salesOrder);
        if (oldStatus != newStatus) {
            recordStatusChange(saved.getId(), oldStatus, newStatus);
        }
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        SalesOrder salesOrder = findById(id);
        if (isStockAffecting(salesOrder.getStatus()) && salesOrder.getWarehouse() != null) {
            increaseStockForItems(salesOrder.getItems(), salesOrder.getWarehouse());
        }
        statusChangeRepository.deleteByOrderTypeAndOrderId(OrderType.SALES, id);
        salesOrderRepository.delete(salesOrder);
    }

    private void fillSalesOrderFromRequest(SalesOrder salesOrder,
                                           Long clientId,
                                           String orderNumber,
                                           OrderStatus status,
                                           String deliveryAddress,
                                           String notes,
                                           LocalDate orderDate,
                                           LocalDate closingDate,
                                           BigDecimal deliveryPrice,
                                           Integer paymentTermDays,
                                           BigDecimal penaltyPercent,
                                           PenaltyPeriod penaltyPeriod,
                                           List<SalesOrderItemRequest> requestItems) {
        Client client = clientService.findById(clientId);

        salesOrder.setClient(client);
        // A blank number means "use the system-allocated one" - mirrors the invoice numbering. The
        // frontend pre-fills the suggestion and sends it back blank when the user keeps it, so the
        // sequence only advances when the system number is actually used.
        salesOrder.setOrderNumber(orderNumber == null || orderNumber.isBlank()
                ? settingsService.allocateNextSalesOrderNumber()
                : orderNumber);
        salesOrder.setStatus(status == null ? OrderStatus.NEW : status);
        salesOrder.setOrderDate(orderDate == null ? LocalDate.now() : orderDate);
        salesOrder.setClosingDate(closingDate);
        salesOrder.setDeliveryAddress(deliveryAddress);
        salesOrder.setNotes(notes);
        salesOrder.setDeliveryPrice(deliveryPrice == null ? BigDecimal.ZERO : deliveryPrice);

        // Optional per-order payment-term overrides (null = fall back to company settings at invoice time).
        salesOrder.setPaymentTermDays(paymentTermDays);
        salesOrder.setPenaltyPercent(penaltyPercent);
        salesOrder.setPenaltyPeriod(penaltyPeriod);

        List<SalesOrderItem> items = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;   // net of tax, after discounts
        BigDecimal taxAmount = BigDecimal.ZERO;

        for (SalesOrderItemRequest itemRequest : requestItems) {
            Product product = productService.findById(itemRequest.getProductId());

            if (itemRequest.getQuantity() == null || itemRequest.getQuantity() <= 0) {
                throw new BadRequestException("error.quantityPositive");
            }

            BigDecimal unitPrice = nz(itemRequest.getUnitPrice());
            BigDecimal discountPercent = clampPercent(itemRequest.getDiscountPercent());
            BigDecimal taxRatePercent = itemRequest.getTaxRatePercent(); // may be null (no tax)

            SalesOrderItem item = new SalesOrderItem();
            item.setSalesOrder(salesOrder);
            item.setProduct(product);
            item.setQuantity(itemRequest.getQuantity());
            item.setUnitPrice(unitPrice);
            item.setDiscountPercent(discountPercent);
            item.setTaxRatePercent(taxRatePercent);

            // Net line: unitPrice * qty * (1 - discount/100); tax computed on top of the net line.
            BigDecimal gross = unitPrice.multiply(BigDecimal.valueOf(itemRequest.getQuantity()));
            BigDecimal lineNet = gross
                    .multiply(BigDecimal.ONE.subtract(discountPercent.movePointLeft(2)))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal lineTax = lineNet.multiply(nz(taxRatePercent).movePointLeft(2))
                    .setScale(2, RoundingMode.HALF_UP);

            item.setLineTotal(lineNet);
            items.add(item);
            subtotal = subtotal.add(lineNet);
            taxAmount = taxAmount.add(lineTax);
        }

        salesOrder.getItems().clear();
        salesOrder.getItems().addAll(items);
        salesOrder.setSubtotalAmount(subtotal);
        salesOrder.setTaxAmount(taxAmount);
        // totalAmount stays net (subtotal + delivery); the tax-inclusive figure is total + tax.
        salesOrder.setTotalAmount(subtotal.add(salesOrder.getDeliveryPrice()));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /** Clamps a discount percentage into [0, 100]; null becomes 0. */
    private static BigDecimal clampPercent(BigDecimal value) {
        if (value == null || value.signum() < 0) return BigDecimal.ZERO;
        return value.compareTo(BigDecimal.valueOf(100)) > 0 ? BigDecimal.valueOf(100) : value;
    }

    @Transactional
    public SalesOrder updateStatus(Long id, OrderStatus newStatus) {
        SalesOrder order = findById(id);
        OrderStatus oldStatus = order.getStatus();
        if (oldStatus == newStatus) return order;
        if (isStockAffecting(oldStatus) && order.getWarehouse() != null) {
            increaseStockForItems(new ArrayList<>(order.getItems()), order.getWarehouse());
        }
        order.setStatus(newStatus);
        if (isStockAffecting(newStatus) && order.getWarehouse() != null) {
            decreaseStockForItems(order.getItems(), order.getWarehouse());
        }
        SalesOrder saved = salesOrderRepository.save(order);
        recordStatusChange(saved.getId(), oldStatus, newStatus);
        return saved;
    }

    private void recordStatusChange(Long orderId, OrderStatus from, OrderStatus to) {
        OrderStatusChange change = new OrderStatusChange();
        change.setOrderType(OrderType.SALES);
        change.setOrderId(orderId);
        change.setFromStatus(from);
        change.setToStatus(to);
        statusChangeRepository.save(change);
    }

    private void decreaseStockForItems(List<SalesOrderItem> items, Warehouse warehouse) {
        for (SalesOrderItem item : items) {
            Product product = productService.findById(item.getProduct().getId());
            // adjustWarehouseStock enforces the hard total-stock limit; allocation then spends specific
            // lots (FEFO/FIFO/LIFO), splitting across them and recording what was used on the line.
            warehouseService.adjustWarehouseStock(warehouse, product, -item.getQuantity());
            productBatchService.allocateForSale(item, product, warehouse, item.getQuantity());
        }
    }

    private void increaseStockForItems(List<SalesOrderItem> items, Warehouse warehouse) {
        for (SalesOrderItem item : items) {
            Product product = productService.findById(item.getProduct().getId());
            // Returning stock (status reverted / order removed): put the spent units back into their
            // lots, then restore the warehouse total.
            productBatchService.reverseSale(item);
            warehouseService.adjustWarehouseStock(warehouse, product, item.getQuantity());
        }
    }
}
