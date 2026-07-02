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
 */
public record TaxRateDto(
        Long id,
        @NotBlank String name,
        @NotNull @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal percentage,
        @JsonProperty("isDefault") boolean isDefault,
        boolean active
) {
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
