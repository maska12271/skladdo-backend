package com.example.skladdo.dto;

import java.math.BigDecimal;

/**
 * One row in a warehouse's stock list: which product, how many units are on hand, and the product's unit
 * price/currency so the frontend can total up the value held in the warehouse.
 */
public record WarehouseStockItemDto(
        Long productId,
        String productName,
        String sku,
        int quantity,
        int minimumStock,
        BigDecimal unitPrice,
        String currency
) {}
