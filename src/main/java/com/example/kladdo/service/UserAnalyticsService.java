package com.example.kladdo.service;

import com.example.kladdo.dto.UserDetailsDto;
import com.example.kladdo.dto.UserDetailsDto.AdjustmentLine;
import com.example.kladdo.dto.UserDetailsDto.OrderLine;
import com.example.kladdo.exception.ResourceNotFoundException;
import com.example.kladdo.model.PurchaseOrder;
import com.example.kladdo.model.SalesOrder;
import com.example.kladdo.repository.InventoryAdjustmentRepository;
import com.example.kladdo.repository.PurchaseOrderRepository;
import com.example.kladdo.repository.SalesOrderRepository;
import com.example.kladdo.repository.UserRepository;
import com.example.kladdo.security.SecurityUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the {@link UserDetailsDto} for the user profile page from the sales and purchase orders a
 * user authored. Read-only and transactional so lazy associations (order -> client/manufacturer and
 * the order item collection) can be traversed with open-in-view disabled.
 */
@Service
public class UserAnalyticsService {

    private final UserRepository userRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final InventoryAdjustmentRepository inventoryAdjustmentRepository;

    public UserAnalyticsService(UserRepository userRepository,
                                SalesOrderRepository salesOrderRepository,
                                PurchaseOrderRepository purchaseOrderRepository,
                                InventoryAdjustmentRepository inventoryAdjustmentRepository) {
        this.userRepository = userRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.inventoryAdjustmentRepository = inventoryAdjustmentRepository;
    }

    @Transactional(readOnly = true)
    public UserDetailsDto getDetails(Long userId) {
        // Tenant isolation: only surface activity for a user in the caller's company.
        userRepository.findByIdAndCompanyId(userId, SecurityUtil.currentCompanyId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        List<OrderLine> salesOrders = salesOrderRepository.findByCreatedById(userId).stream()
                .map(UserAnalyticsService::salesLine)
                .sorted(byDateDesc())
                .toList();

        List<OrderLine> purchaseOrders = purchaseOrderRepository.findByCreatedById(userId).stream()
                .map(UserAnalyticsService::purchaseLine)
                .sorted(byDateDesc())
                .toList();

        List<AdjustmentLine> inventoryAdjustments = inventoryAdjustmentRepository
                .findByCreatedByIdOrderByCreatedAtDescIdDesc(userId).stream()
                .map(a -> new AdjustmentLine(
                        a.getId(),
                        a.getProduct() != null ? a.getProduct().getId() : null,
                        a.getProduct() != null ? a.getProduct().getName() : null,
                        a.getQuantityChange() != null ? a.getQuantityChange() : 0,
                        a.getNewQuantity() != null ? a.getNewQuantity() : 0,
                        a.getNote(),
                        a.getCreatedAt()))
                .toList();

        return new UserDetailsDto(salesOrders, purchaseOrders, inventoryAdjustments);
    }

    private static OrderLine salesLine(SalesOrder order) {
        return new OrderLine(
                order.getId(),
                order.getOrderNumber(),
                order.getOrderDate(),
                order.getStatus() != null ? order.getStatus().name() : null,
                order.getClient() != null ? order.getClient().getName() : null,
                order.getItems() != null ? order.getItems().size() : 0,
                nz(order.getTotalAmount())
        );
    }

    private static OrderLine purchaseLine(PurchaseOrder order) {
        return new OrderLine(
                order.getId(),
                order.getOrderNumber(),
                order.getOrderDate(),
                order.getStatus() != null ? order.getStatus().name() : null,
                order.getManufacturer() != null ? order.getManufacturer().getName() : null,
                order.getItems() != null ? order.getItems().size() : 0,
                nz(order.getTotalAmount())
        );
    }

    private static Comparator<OrderLine> byDateDesc() {
        return Comparator.comparing(OrderLine::orderDate, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
