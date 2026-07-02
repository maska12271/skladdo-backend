package com.example.kladdo.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request to move stock of a product between two warehouses. When {@code batchId} is supplied the
 * units are taken from that specific lot (preserving its production/expiry dates in the destination);
 * otherwise a flat quantity is moved (for products without lot tracking).
 */
public record CreateStockTransferRequest(
        @NotNull Long fromWarehouseId,
        @NotNull Long toWarehouseId,
        @NotNull @Min(1) Integer quantity,
        Long batchId,
        String note
) {
}
