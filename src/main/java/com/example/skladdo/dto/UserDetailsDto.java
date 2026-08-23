package com.example.skladdo.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Activity a single user is responsible for: the sales and purchase orders they created, plus the
 * inventory (stock) adjustments they made. The user detail page filters these by period client-side
 * and aggregates them — mirroring how the product detail page consumes its order lines.
 */
public record UserDetailsDto(
        List<OrderLine> salesOrders,
        List<OrderLine> purchaseOrders,
        List<AdjustmentLine> inventoryAdjustments
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

    /** One stock adjustment the user made: the signed change, resulting quantity, reason and when. */
    public record AdjustmentLine(
            Long id,
            Long productId,
            String productName,
            int quantityChange,
            int newQuantity,
            String note,
            Instant createdAt
    ) {
    }
}
