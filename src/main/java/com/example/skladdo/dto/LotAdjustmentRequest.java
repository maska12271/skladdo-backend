package com.example.skladdo.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Stock-take across one or more lots: each item carries a signed change for a specific lot. A single
 * shared reason note is recorded against every resulting adjustment. Items with a zero change are
 * ignored; at least one non-zero item is required.
 */
public record LotAdjustmentRequest(
        @Size(max = 1000) String note,
        @NotEmpty List<Item> items
) {
    public record Item(
            @NotNull Long batchId,
            @NotNull Integer quantityChange
    ) {
    }
}
