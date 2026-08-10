package com.example.kladdo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Records how much of each order line has been picked (sales) or received (purchase). Lines the caller
 * omits are left untouched, so a warehouse worker can check items in a few at a time.
 *
 * <p>Wrapper types throughout: an absent primitive would fail to deserialize under Jackson 3.</p>
 */
public record UpdateFulfilmentRequest(
        @NotEmpty @Valid List<LineFulfilment> lines
) {
    public record LineFulfilment(
            @NotNull Long lineId,
            @NotNull @Min(0) Integer quantity
    ) {
    }
}
