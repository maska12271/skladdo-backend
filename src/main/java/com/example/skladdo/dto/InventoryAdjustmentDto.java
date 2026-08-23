package com.example.skladdo.dto;

import com.example.skladdo.model.InventoryAdjustment;

import java.time.Instant;

/**
 * One row of a product's stock-adjustment history: the signed change, the resulting quantity, the
 * warehouse, the reason and who made it / when.
 */
public record InventoryAdjustmentDto(
        Long id,
        Long productId,
        Long warehouseId,
        String warehouseName,
        Integer quantityChange,
        Integer newQuantity,
        String note,
        Instant createdAt,
        Actor createdBy
) {
    public record Actor(Long id, String name) {
    }

    public static InventoryAdjustmentDto from(InventoryAdjustment adjustment, Actor createdBy) {
        return new InventoryAdjustmentDto(
                adjustment.getId(),
                adjustment.getProduct() != null ? adjustment.getProduct().getId() : null,
                adjustment.getWarehouse() != null ? adjustment.getWarehouse().getId() : null,
                adjustment.getWarehouse() != null ? adjustment.getWarehouse().getName() : null,
                adjustment.getQuantityChange(),
                adjustment.getNewQuantity(),
                adjustment.getNote(),
                adjustment.getCreatedAt(),
                createdBy
        );
    }
}
