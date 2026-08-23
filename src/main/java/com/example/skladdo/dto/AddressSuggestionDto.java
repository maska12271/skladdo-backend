package com.example.skladdo.dto;

/**
 * One address typeahead suggestion. {@code address} is the full human-readable address to drop into
 * the field; {@code postalCode} is the postal (sihtnumber) code when the provider supplies one.
 */
public record AddressSuggestionDto(
        String address,
        String postalCode
) {
}
