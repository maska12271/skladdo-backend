package com.example.kladdo.dto;

import com.example.kladdo.model.ProductBatch;

import java.time.LocalDate;

/** A single in-stock lot of a product, for the product detail "stock by lot" view. */
public record ProductBatchDto(
        Long id,
        Long warehouseId,
        String warehouseName,
        String lotNumber,
        Integer quantity,
        Integer originalQuantity,
        LocalDate productionDate,
        LocalDate expiryDate
) {
    public static ProductBatchDto from(ProductBatch b) {
        return new ProductBatchDto(
                b.getId(),
                b.getWarehouse().getId(),
                b.getWarehouse().getName(),
                b.getLotNumber(),
                b.getQuantity(),
                b.getOriginalQuantity(),
                b.getProductionDate(),
                b.getExpiryDate()
        );
    }
}
