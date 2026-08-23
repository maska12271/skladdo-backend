package com.example.skladdo.dto;

import java.time.LocalDate;

/**
 * Result of looking up whether a product already has a lot with a given number. When it does, the UI
 * locks the production/expiry inputs to the existing values so a lot's identity stays consistent.
 */
public record LotLookupDto(
        boolean exists,
        LocalDate productionDate,
        LocalDate expiryDate
) {
}
