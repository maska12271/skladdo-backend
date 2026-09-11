package com.example.skladdo.dto;

/**
 * One address typeahead suggestion. {@code address} is the full human-readable line, still used by the
 * fields that store an address as free text (manufacturers, warehouses, delivery addresses); the parts
 * beside it let the structured client and company address forms fill every input from one pick, which is
 * what keeps a required city from being left empty.
 */
public record AddressSuggestionDto(
        String address,
        String postalCode,
        String street,
        String city,
        String country
) {
}
