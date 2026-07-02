package com.example.kladdo.service;

import com.example.kladdo.dto.CreatePurchaseOrderRequest;
import com.example.kladdo.dto.PurchaseOrderItemRequest;
import com.example.kladdo.dto.UpdatePurchaseOrderRequest;
import com.example.kladdo.exception.BadRequestException;
import com.example.kladdo.exception.ResourceNotFoundException;
import com.example.kladdo.model.Manufacturer;
import com.example.kladdo.model.OrderStatus;
import com.example.kladdo.model.OrderStatusChange;
import com.example.kladdo.model.OrderType;
import com.example.kladdo.model.Product;
import com.example.kladdo.model.PurchaseOrder;
import com.example.kladdo.model.PurchaseOrderItem;
import com.example.kladdo.model.Role;
import com.example.kladdo.model.Warehouse;
import com.example.kladdo.repository.OrderStatusChangeRepository;
import com.example.kladdo.repository.PurchaseOrderRepository;
import com.example.kladdo.security.SecurityUtil;
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
    private final WarehouseService warehouseService;
    private final ProductBatchService productBatchService;
    private final CompanySettingsService settingsService;

    public PurchaseOrderService(PurchaseOrderRepository purchaseOrderRepository,
                                ManufacturerService manufacturerService,
                                ProductService productService,
                                OrderStatusChangeRepository statusChangeRepository,
                                WarehouseService warehouseService,
                                ProductBatchService productBatchService,
                                CompanySettingsService settingsService) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.manufacturerService = manufacturerService;
        this.productService = productService;
        this.statusChangeRepository = statusChangeRepository;
        this.warehouseService = warehouseService;
        this.productBatchService = productBatchService;
        this.settingsService = settingsService;
    }

    @Transactional(readOnly = true)
    public Page<PurchaseOrder> findAll(String search, List<Long> manufacturerIds, List<OrderStatus> statuses,
                                       Long warehouseId, LocalDate dateFrom, LocalDate dateTo, Pageable pageable) {
        List<Long> accessibleWarehouseIds = warehouseService.getAccessibleWarehouseIds();

        Specification<PurchaseOrder> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (search != null && !search.isBlank()) {
                String like = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("orderNumber")), like),
                        cb.like(cb.lower(root.get("manufacturer").get("name")), like)
                ));
            }
            if (manufacturerIds != null && !manufacturerIds.isEmpty()) {
                predicates.add(root.get("manufacturer").get("id").in(manufacturerIds));
            }
            if (statuses != null && !statuses.isEmpty()) {
                predicates.add(root.get("status").in(statuses));
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
            // WAREHOUSE role: restrict to their assigned warehouses.
            if (accessibleWarehouseIds != null) {
                if (accessibleWarehouseIds.isEmpty()) {
                    predicates.add(cb.disjunction()); // no warehouses assigned → no rows
                } else {
                    predicates.add(root.get("warehouse").get("id").in(accessibleWarehouseIds));
                }
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<PurchaseOrder> page = purchaseOrderRepository.findAll(specification, pageable);
        page.forEach(order -> order.getItems().size());
        return page;
    }

    @Transactional(readOnly = true)
    public PurchaseOrder findById(Long id) {
        PurchaseOrder order = purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase order not found with id: " + id));
        order.getItems().size();
        return order;
    }

    private static boolean isStockAffecting(OrderStatus status) {
        return status == OrderStatus.SHIPPED || status == OrderStatus.CLOSED;
    }

    @Transactional
    public PurchaseOrder create(CreatePurchaseOrderRequest request) {
        Warehouse warehouse = warehouseService.requireAccess(request.getWarehouseId());
        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setWarehouse(warehouse);

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
        purchaseOrder.setInvoiceFileUrl(blankToNull(request.getInvoiceFileUrl()));
        purchaseOrder.setInvoiceFileName(blankToNull(request.getInvoiceFileName()));

        if (isStockAffecting(purchaseOrder.getStatus())) {
            increaseStockForItems(purchaseOrder.getItems(), warehouse);
        }

        PurchaseOrder saved = purchaseOrderRepository.save(purchaseOrder);
        recordStatusChange(saved.getId(), null, saved.getStatus());
        return saved;
    }

    @Transactional
    public PurchaseOrder update(Long id, UpdatePurchaseOrderRequest request) {
        PurchaseOrder purchaseOrder = findById(id);

        OrderStatus oldStatus = purchaseOrder.getStatus();
        Warehouse oldWarehouse = purchaseOrder.getWarehouse();
        List<PurchaseOrderItem> oldItems = new ArrayList<>(purchaseOrder.getItems());

        Warehouse newWarehouse = warehouseService.requireAccess(request.getWarehouseId());
        purchaseOrder.setWarehouse(newWarehouse);

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
        purchaseOrder.setInvoiceFileUrl(blankToNull(request.getInvoiceFileUrl()));
        purchaseOrder.setInvoiceFileName(blankToNull(request.getInvoiceFileName()));

        OrderStatus newStatus = purchaseOrder.getStatus();

        if (isStockAffecting(oldStatus) && oldWarehouse != null) {
            decreaseStockForItems(oldItems, oldWarehouse);
        }
        if (isStockAffecting(newStatus)) {
            increaseStockForItems(purchaseOrder.getItems(), newWarehouse);
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
        if (isStockAffecting(purchaseOrder.getStatus()) && purchaseOrder.getWarehouse() != null) {
            decreaseStockForItems(purchaseOrder.getItems(), purchaseOrder.getWarehouse());
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
        // A blank number means "use the system-allocated one" - mirrors the sales-order numbering. The
        // frontend pre-fills the suggestion and sends it back blank when the user keeps it, so the
        // sequence only advances when the system number is actually used.
        purchaseOrder.setOrderNumber(orderNumber == null || orderNumber.isBlank()
                ? settingsService.allocateNextPurchaseOrderNumber()
                : orderNumber);
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
                throw new BadRequestException("error.quantityPositive");
            }

            BigDecimal unitPrice = itemRequest.getUnitPrice() == null ? BigDecimal.ZERO : itemRequest.getUnitPrice();

            PurchaseOrderItem item = new PurchaseOrderItem();
            item.setPurchaseOrder(purchaseOrder);
            item.setProduct(product);
            item.setQuantity(itemRequest.getQuantity());
            item.setUnitPrice(unitPrice);

            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(itemRequest.getQuantity()));

            item.setLineTotal(lineTotal);
            item.setLotNumber(itemRequest.getLotNumber() == null || itemRequest.getLotNumber().isBlank()
                    ? null : itemRequest.getLotNumber().trim());
            item.setProductionDate(itemRequest.getProductionDate());
            item.setExpiryDate(itemRequest.getExpiryDate());
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
        if (isStockAffecting(oldStatus) && order.getWarehouse() != null) {
            decreaseStockForItems(new ArrayList<>(order.getItems()), order.getWarehouse());
        }
        order.setStatus(newStatus);
        if (isStockAffecting(newStatus) && order.getWarehouse() != null) {
            increaseStockForItems(order.getItems(), order.getWarehouse());
        }
        PurchaseOrder saved = purchaseOrderRepository.save(order);
        recordStatusChange(saved.getId(), oldStatus, newStatus);
        return saved;
    }

    /** Attaches (or, with both args null, removes) the supplier invoice document on an order. */
    @Transactional
    public PurchaseOrder setInvoiceFile(Long id, String url, String name) {
        PurchaseOrder order = findById(id);
        order.setInvoiceFileUrl(blankToNull(url));
        order.setInvoiceFileName(blankToNull(name));
        return purchaseOrderRepository.save(order);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void recordStatusChange(Long orderId, OrderStatus from, OrderStatus to) {
        OrderStatusChange change = new OrderStatusChange();
        change.setOrderType(OrderType.PURCHASE);
        change.setOrderId(orderId);
        change.setFromStatus(from);
        change.setToStatus(to);
        statusChangeRepository.save(change);
    }

    private void increaseStockForItems(List<PurchaseOrderItem> items, Warehouse warehouse) {
        for (PurchaseOrderItem item : items) {
            Product product = productService.findById(item.getProduct().getId());
            warehouseService.adjustWarehouseStock(warehouse, product, item.getQuantity());
            // Receiving goods: open or top up the lot so it becomes sellable stock.
            productBatchService.receiveBatch(product, warehouse, item.getLotNumber(), item.getQuantity(),
                    item.getProductionDate(), item.getExpiryDate());
        }
    }

    private void decreaseStockForItems(List<PurchaseOrderItem> items, Warehouse warehouse) {
        for (PurchaseOrderItem item : items) {
            Product product = productService.findById(item.getProduct().getId());
            warehouseService.adjustWarehouseStock(warehouse, product, -item.getQuantity());
            // Un-receiving (status reverted / order removed): take the received units back out of the lot.
            productBatchService.reverseReceipt(product, warehouse, item.getLotNumber(), item.getQuantity());
        }
    }
}
