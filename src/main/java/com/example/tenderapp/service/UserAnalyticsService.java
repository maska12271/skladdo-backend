package com.example.tenderapp.service;

import com.example.tenderapp.dto.UserDetailsDto;
import com.example.tenderapp.dto.UserDetailsDto.OrderLine;
import com.example.tenderapp.exception.ResourceNotFoundException;
import com.example.tenderapp.model.PurchaseOrder;
import com.example.tenderapp.model.SalesOrder;
import com.example.tenderapp.repository.PurchaseOrderRepository;
import com.example.tenderapp.repository.SalesOrderRepository;
import com.example.tenderapp.repository.UserRepository;
import com.example.tenderapp.security.SecurityUtil;
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

    public UserAnalyticsService(UserRepository userRepository,
                                SalesOrderRepository salesOrderRepository,
                                PurchaseOrderRepository purchaseOrderRepository) {
        this.userRepository = userRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
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

        return new UserDetailsDto(salesOrders, purchaseOrders);
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
