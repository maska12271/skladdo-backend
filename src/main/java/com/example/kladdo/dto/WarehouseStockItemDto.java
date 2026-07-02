package com.example.kladdo.dto;

/** One row in a warehouse's stock list: which product and how many units are on hand. */
public record WarehouseStockItemDto(
        Long productId,
        String productName,
        String sku,
        int quantity,
        int minimumStock
) {}
