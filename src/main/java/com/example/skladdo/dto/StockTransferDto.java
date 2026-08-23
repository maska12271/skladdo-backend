package com.example.skladdo.dto;

import com.example.skladdo.model.StockTransfer;

import java.time.Instant;

/**
 * A single stock-transfer record for the product detail history. The {@code by} actor name is resolved
 * by the service (the entity only stores the creator's id).
 */
public record StockTransferDto(
        Long id,
        Long fromWarehouseId,
        String fromWarehouseName,
        Long toWarehouseId,
        String toWarehouseName,
        String lotNumber,
        int quantity,
        String note,
        Instant createdAt,
        Actor by
) {
    public record Actor(Long id, String name) {
    }

    public static StockTransferDto from(StockTransfer t, Actor by) {
        return new StockTransferDto(
                t.getId(),
                t.getFromWarehouse() != null ? t.getFromWarehouse().getId() : null,
                t.getFromWarehouse() != null ? t.getFromWarehouse().getName() : null,
                t.getToWarehouse() != null ? t.getToWarehouse().getId() : null,
                t.getToWarehouse() != null ? t.getToWarehouse().getName() : null,
                t.getLotNumber(),
                t.getQuantity() != null ? t.getQuantity() : 0,
                t.getNote(),
                t.getCreatedAt(),
                by
        );
    }
}
