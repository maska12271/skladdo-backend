package com.example.tenderapp.service;

import com.example.tenderapp.dto.CreatePurchaseOrderRequest;
import com.example.tenderapp.dto.PurchaseOrderItemRequest;
import com.example.tenderapp.dto.UpdatePurchaseOrderRequest;
import com.example.tenderapp.exception.ResourceNotFoundException;
import com.example.tenderapp.model.Manufacturer;
import com.example.tenderapp.model.OrderStatus;
import com.example.tenderapp.model.OrderStatusChange;
import com.example.tenderapp.model.OrderType;
import com.example.tenderapp.model.Product;
import com.example.tenderapp.model.PurchaseOrder;
import com.example.tenderapp.model.PurchaseOrderItem;
import com.example.tenderapp.repository.OrderStatusChangeRepository;
import com.example.tenderapp.repository.PurchaseOrderRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class PurchaseOrderService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final ManufacturerService manufacturerService;
    private final ProductService productService;
    private final OrderStatusChangeRepository statusChangeRepository;

    public PurchaseOrderService(PurchaseOrderRepository purchaseOrderRepository,
                                ManufacturerService manufacturerService,
                                ProductService productService,
                                OrderStatusChangeRepository statusChangeRepository) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.manufacturerService = manufacturerService;
        this.productService = productService;
        this.statusChangeRepository = statusChangeRepository;
    }

    @Transactional(readOnly = true)
    public Page<PurchaseOrder> findAll(Long manufacturerId, OrderStatus status, LocalDate dateFrom, LocalDate dateTo, Pageable pageable) {
        Specification<PurchaseOrder> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (manufacturerId != null) {
                predicates.add(cb.equal(root.get("manufacturer").get("id"), manufacturerId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (dateFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("orderDate"), dateFrom));
            }
            if (dateTo != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("orderDate"), dateTo));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<PurchaseOrder> page = purchaseOrderRepository.findAll(specification, pageable);
        // Initialize the lazy item collections before the transaction closes so they serialize
        // into the JSON response (open-in-view is disabled).
        page.forEach(order -> order.getItems().size());
        return page;
    }

    @Transactional(readOnly = true)
    public PurchaseOrder findById(Long id) {
        PurchaseOrder order = purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase order not found with id: " + id));
        order.getItems().size(); // initialize lazy items for serialization
        return order;
    }

    // Stock is added to the warehouse only when the order is shipped (received) or closed.
    private static boolean isStockAffecting(OrderStatus status) {
        return status == OrderStatus.SHIPPED || status == OrderStatus.CLOSED;
    }

    @Transactional
    public PurchaseOrder create(CreatePurchaseOrderRequest request) {
        PurchaseOrder purchaseOrder = new PurchaseOrder();

        fillPurchaseOrderFromRequest(
                purchaseOrder,
                request.getManufacturerId(),
                request.getOrderNumber(),
                request.getStatus(),
                request.getDeliveryAddress(),
                request.getNotes(),
                request.getOrderDate(),
                request.getClosingDate(),
                request.getExpectedDeliveryDate(),
                request.getDeliveryPrice(),
                request.getItems()
        );

        if (isStockAffecting(purchaseOrder.getStatus())) {
            increaseStockForItems(purchaseOrder.getItems());
        }

        PurchaseOrder saved = purchaseOrderRepository.save(purchaseOrder);
        recordStatusChange(saved.getId(), null, saved.getStatus());
        return saved;
    }

    @Transactional
    public PurchaseOrder update(Long id, UpdatePurchaseOrderRequest request) {
        PurchaseOrder purchaseOrder = findById(id);

        OrderStatus oldStatus = purchaseOrder.getStatus();
        List<PurchaseOrderItem> oldItems = new ArrayList<>(purchaseOrder.getItems());

        fillPurchaseOrderFromRequest(
                purchaseOrder,
                request.getManufacturerId(),
                request.getOrderNumber(),
                request.getStatus(),
                request.getDeliveryAddress(),
                request.getNotes(),
                request.getOrderDate(),
                request.getClosingDate(),
                request.getExpectedDeliveryDate(),
                request.getDeliveryPrice(),
                request.getItems()
        );

        OrderStatus newStatus = purchaseOrder.getStatus();

        // Reverse the old stock increase if the order was previously affecting stock.
        if (isStockAffecting(oldStatus)) {
            decreaseStockForItems(oldItems);
        }
        // Apply a new stock increase if the order is now affecting stock.
        if (isStockAffecting(newStatus)) {
            increaseStockForItems(purchaseOrder.getItems());
        }

        PurchaseOrder saved = purchaseOrderRepository.save(purchaseOrder);
        if (oldStatus != newStatus) {
            recordStatusChange(saved.getId(), oldStatus, newStatus);
        }
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        PurchaseOrder purchaseOrder = findById(id);
        // Only remove stock if it was actually added (order was shipped/closed).
        if (isStockAffecting(purchaseOrder.getStatus())) {
            decreaseStockForItems(purchaseOrder.getItems());
        }
        statusChangeRepository.deleteByOrderTypeAndOrderId(OrderType.PURCHASE, id);
        purchaseOrderRepository.delete(purchaseOrder);
    }

    private void fillPurchaseOrderFromRequest(PurchaseOrder purchaseOrder,
                                              Long manufacturerId,
                                              String orderNumber,
                                              OrderStatus status,
                                              String deliveryAddress,
                                              String notes,
                                              LocalDate orderDate,
                                              LocalDate closingDate,
                                              LocalDate expectedDeliveryDate,
                                              BigDecimal deliveryPrice,
                                              List<PurchaseOrderItemRequest> requestItems) {
        Manufacturer manufacturer = manufacturerService.findById(manufacturerId);

        purchaseOrder.setManufacturer(manufacturer);
        purchaseOrder.setOrderNumber(orderNumber == null || orderNumber.isBlank() ? "PO-" + System.currentTimeMillis() : orderNumber);
        purchaseOrder.setStatus(status == null ? OrderStatus.NEW : status);
        purchaseOrder.setOrderDate(orderDate == null ? LocalDate.now() : orderDate);
        purchaseOrder.setClosingDate(closingDate);
        purchaseOrder.setExpectedDeliveryDate(expectedDeliveryDate);
        purchaseOrder.setDeliveryAddress(deliveryAddress);
        purchaseOrder.setNotes(notes);
        purchaseOrder.setDeliveryPrice(deliveryPrice == null ? BigDecimal.ZERO : deliveryPrice);

        List<PurchaseOrderItem> items = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;

        for (PurchaseOrderItemRequest itemRequest : requestItems) {
            Product product = productService.findById(itemRequest.getProductId());

            if (itemRequest.getQuantity() == null || itemRequest.getQuantity() <= 0) {
                throw new IllegalArgumentException("Quantity must be greater than 0");
            }

            BigDecimal unitPrice = itemRequest.getUnitPrice() == null ? BigDecimal.ZERO : itemRequest.getUnitPrice();

            PurchaseOrderItem item = new PurchaseOrderItem();
            item.setPurchaseOrder(purchaseOrder);
            item.setProduct(product);
            item.setQuantity(itemRequest.getQuantity());
            item.setUnitPrice(unitPrice);

            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(itemRequest.getQuantity()));

            item.setLineTotal(lineTotal);
            items.add(item);
            subtotal = subtotal.add(lineTotal);
        }

        purchaseOrder.getItems().clear();
        purchaseOrder.getItems().addAll(items);
        purchaseOrder.setSubtotalAmount(subtotal);
        purchaseOrder.setTotalAmount(subtotal.add(purchaseOrder.getDeliveryPrice()));
    }

    @Transactional
    public PurchaseOrder updateStatus(Long id, OrderStatus newStatus) {
        PurchaseOrder order = findById(id);
        OrderStatus oldStatus = order.getStatus();
        if (oldStatus == newStatus) return order;
        if (isStockAffecting(oldStatus)) {
            decreaseStockForItems(new ArrayList<>(order.getItems()));
        }
        order.setStatus(newStatus);
        if (isStockAffecting(newStatus)) {
            increaseStockForItems(order.getItems());
        }
        PurchaseOrder saved = purchaseOrderRepository.save(order);
        recordStatusChange(saved.getId(), oldStatus, newStatus);
        return saved;
    }

    private void recordStatusChange(Long orderId, OrderStatus from, OrderStatus to) {
        OrderStatusChange change = new OrderStatusChange();
        change.setOrderType(OrderType.PURCHASE);
        change.setOrderId(orderId);
        change.setFromStatus(from);
        change.setToStatus(to);
        statusChangeRepository.save(change);
    }

    private void increaseStockForItems(List<PurchaseOrderItem> items) {
        for (PurchaseOrderItem item : items) {
            Product product = productService.findById(item.getProduct().getId());

            Integer currentStock = product.getStockQuantity();
            int safeCurrentStock = currentStock == null ? 0 : currentStock;
            int qty = item.getQuantity();

            product.setStockQuantity(safeCurrentStock + qty);
            productService.save(product);
        }
    }

    private void decreaseStockForItems(List<PurchaseOrderItem> items) {
        for (PurchaseOrderItem item : items) {
            Product product = productService.findById(item.getProduct().getId());

            Integer currentStock = product.getStockQuantity();
            int safeCurrentStock = currentStock == null ? 0 : currentStock;
            int qty = item.getQuantity();

            if (safeCurrentStock < qty) {
                throw new IllegalArgumentException("Cannot remove purchase quantity from stock for product: " + product.getName());
            }

            product.setStockQuantity(safeCurrentStock - qty);
            productService.save(product);
        }
    }
}