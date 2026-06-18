package com.example.tenderapp.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Raw purchase-order lines a manufacturer (supplier) appears in. The detail page aggregates these
 * client-side into per-order and per-product tables, a monthly time series and summary cards —
 * mirroring how the product detail page consumes its order lines.
 */
public record ManufacturerDetailsDto(
        int catalogProductCount,
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
