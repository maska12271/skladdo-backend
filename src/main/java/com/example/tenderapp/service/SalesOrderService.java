package com.example.tenderapp.service;

import com.example.tenderapp.dto.CreateSalesOrderRequest;
import com.example.tenderapp.dto.SalesOrderItemRequest;
import com.example.tenderapp.dto.UpdateSalesOrderRequest;
import com.example.tenderapp.exception.ResourceNotFoundException;
import com.example.tenderapp.model.Client;
import com.example.tenderapp.model.OrderStatus;
import com.example.tenderapp.model.Product;
import com.example.tenderapp.model.SalesOrder;
import com.example.tenderapp.model.SalesOrderItem;
import com.example.tenderapp.repository.SalesOrderRepository;
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
public class SalesOrderService {

    private final SalesOrderRepository salesOrderRepository;
    private final ClientService clientService;
    private final ProductService productService;

    public SalesOrderService(SalesOrderRepository salesOrderRepository,
                             ClientService clientService,
                             ProductService productService) {
        this.salesOrderRepository = salesOrderRepository;
        this.clientService = clientService;
        this.productService = productService;
    }

    public Page<SalesOrder> findAll(Long clientId, OrderStatus status, LocalDate dateFrom, LocalDate dateTo, Pageable pageable) {
        Specification<SalesOrder> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (clientId != null) {
                predicates.add(cb.equal(root.get("client").get("id"), clientId));
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

        return salesOrderRepository.findAll(specification, pageable);
    }

    public SalesOrder findById(Long id) {
        return salesOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sales order not found with id: " + id));
    }

    @Transactional
    public SalesOrder create(CreateSalesOrderRequest request) {
        SalesOrder salesOrder = new SalesOrder();

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
                request.getItems()
        );

        decreaseStockForItems(salesOrder.getItems());

        return salesOrderRepository.save(salesOrder);
    }

    @Transactional
    public SalesOrder update(Long id, UpdateSalesOrderRequest request) {
        SalesOrder salesOrder = findById(id);

        increaseStockForItems(salesOrder.getItems());

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
                request.getItems()
        );

        decreaseStockForItems(salesOrder.getItems());

        return salesOrderRepository.save(salesOrder);
    }

    @Transactional
    public void delete(Long id) {
        SalesOrder salesOrder = findById(id);
        increaseStockForItems(salesOrder.getItems());
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
                                           List<SalesOrderItemRequest> requestItems) {
        Client client = clientService.findById(clientId);

        salesOrder.setClient(client);
        salesOrder.setOrderNumber(orderNumber == null || orderNumber.isBlank() ? "SO-" + System.currentTimeMillis() : orderNumber);
        salesOrder.setStatus(status == null ? OrderStatus.NEW : status);
        salesOrder.setOrderDate(orderDate == null ? LocalDate.now() : orderDate);
        salesOrder.setClosingDate(closingDate);
        salesOrder.setDeliveryAddress(deliveryAddress);
        salesOrder.setNotes(notes);
        salesOrder.setDeliveryPrice(deliveryPrice == null ? BigDecimal.ZERO : deliveryPrice);

        List<SalesOrderItem> items = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;

        for (SalesOrderItemRequest itemRequest : requestItems) {
            Product product = productService.findById(itemRequest.getProductId());

            if (itemRequest.getQuantity() == null || itemRequest.getQuantity() <= 0) {
                throw new IllegalArgumentException("Quantity must be greater than 0");
            }

            BigDecimal unitPrice = itemRequest.getUnitPrice() == null ? BigDecimal.ZERO : itemRequest.getUnitPrice();

            SalesOrderItem item = new SalesOrderItem();
            item.setSalesOrder(salesOrder);
            item.setProduct(product);
            item.setQuantity(itemRequest.getQuantity());
            item.setUnitPrice(unitPrice);

            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(itemRequest.getQuantity()));

            item.setLineTotal(lineTotal);
            items.add(item);
            subtotal = subtotal.add(lineTotal);
        }

        salesOrder.getItems().clear();
        salesOrder.getItems().addAll(items);
        salesOrder.setSubtotalAmount(subtotal);
        salesOrder.setTotalAmount(subtotal.add(salesOrder.getDeliveryPrice()));
    }

    private void decreaseStockForItems(List<SalesOrderItem> items) {
        for (SalesOrderItem item : items) {
            Product product = productService.findById(item.getProduct().getId());

            Integer currentStock = product.getStockQuantity();
            int safeCurrentStock = currentStock == null ? 0 : currentStock;
            int qty = item.getQuantity();

            if (safeCurrentStock < qty) {
                throw new IllegalArgumentException("Not enough stock for product: " + product.getName());
            }

            product.setStockQuantity(safeCurrentStock - qty);
            productService.save(product);
        }
    }

    private void increaseStockForItems(List<SalesOrderItem> items) {
        for (SalesOrderItem item : items) {
            Product product = productService.findById(item.getProduct().getId());

            Integer currentStock = product.getStockQuantity();
            int safeCurrentStock = currentStock == null ? 0 : currentStock;
            int qty = item.getQuantity();

            product.setStockQuantity(safeCurrentStock + qty);
            productService.save(product);
        }
    }
}