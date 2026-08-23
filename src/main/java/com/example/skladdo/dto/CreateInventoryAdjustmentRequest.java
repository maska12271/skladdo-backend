package com.example.skladdo.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request to adjust a product's stock in a specific warehouse. {@code quantityChange} is signed:
 * positive adds stock, negative removes it. Zero is rejected. The reason {@code note} is optional
 * but encouraged.
 */
public record CreateInventoryAdjustmentRequest(
        @NotNull Long warehouseId,
        @NotNull Integer quantityChange,
        @Size(max = 1000) String note
) {
}
