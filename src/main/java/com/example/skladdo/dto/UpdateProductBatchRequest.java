package com.example.skladdo.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

/** Edit a lot's identifying details (its quantity is changed only through adjustments). */
public record UpdateProductBatchRequest(
        @NotBlank String lotNumber,
        LocalDate productionDate,
        LocalDate expiryDate
) {
}
