package com.example.kladdo.dto;

/** Per-warehouse stock entry for a single product (shown on the product detail page). */
public record ProductWarehouseStockDto(
        Long warehouseId,
        String warehouseName,
        int quantity
) {}
