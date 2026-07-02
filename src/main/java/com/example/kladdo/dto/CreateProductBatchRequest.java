package com.example.kladdo.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Request to receive stock into a lot on the product detail page. Creates the lot in the warehouse
 * (or tops it up if it already exists) and bumps the warehouse + product stock. When a lot with the
 * same number already exists for the product, its production/expiry dates win over any supplied here.
 */
public record CreateProductBatchRequest(
        @NotNull Long warehouseId,
        @NotBlank String lotNumber,
        @NotNull @Min(1) Integer quantity,
        LocalDate productionDate,
        LocalDate expiryDate,
        @Size(max = 1000) String note
) {
}
