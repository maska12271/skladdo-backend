package com.example.skladdo.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Raw sales-order lines a client (buyer) appears in. The detail page aggregates these client-side
 * into per-order and per-product tables, a monthly time series and summary cards — mirroring how
 * the product detail page consumes its order lines.
 */
public record ClientDetailsDto(
        List<OrderLine> lines
) {

    public record OrderLine(
            Long orderId,
            String orderNumber,
            LocalDate orderDate,
            String status,
            Long productId,
            String productName,
            String sku,
            int quantity,
            BigDecimal lineTotal
    ) {
    }
}
