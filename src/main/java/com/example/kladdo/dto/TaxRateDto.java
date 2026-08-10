package com.example.kladdo.dto;

import com.example.kladdo.model.TaxRate;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * A configurable tax rate. {@code id} is null on create; {@code isDefault}/{@code active} default to
 * sensible values when omitted. The {@code isDefault} JSON name is pinned so the {@code is} prefix is
 * never collapsed to {@code default} by the serializer.
 *
 * <p>Both flags are {@link Boolean} rather than primitives so that "omitted" is actually expressible.
 * As primitives they had no value to bind to when absent, so the documented defaulting above did not in
 * fact happen — a body without them was rejected outright as unreadable (finding N-007).</p>
 */
public record TaxRateDto(
        Long id,
        @NotBlank String name,
        @NotNull @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal percentage,
        @JsonProperty("isDefault") Boolean isDefault,
        Boolean active
) {
    /** Not the default unless the caller says so (the service still promotes the first rate). */
    public boolean isDefaultOrFalse() {
        return isDefault != null && isDefault;
    }

    /** A rate is usable unless the caller explicitly deactivates it. */
    public boolean activeOrDefault() {
        return active == null || active;
    }

    public static TaxRateDto from(TaxRate rate) {
        return new TaxRateDto(
                rate.getId(),
                rate.getName(),
                rate.getPercentage(),
                rate.isDefault(),
                rate.isActive()
        );
    }
}
