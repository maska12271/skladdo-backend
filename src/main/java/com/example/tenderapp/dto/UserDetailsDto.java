package com.example.tenderapp.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Activity a single user is responsible for: the sales and purchase orders they created. The user
 * detail page filters these by period client-side and aggregates them into summary cards, a monthly
 * time series and tables — mirroring how the product detail page consumes its order lines.
 */
public record UserDetailsDto(
        List<OrderLine> salesOrders,
        List<OrderLine> purchaseOrders
) {

    public record OrderLine(
            Long orderId,
            String orderNumber,
            LocalDate orderDate,
            String status,
            String counterpartyName,
            int itemCount,
            BigDecimal totalAmount
    ) {
    }
}
