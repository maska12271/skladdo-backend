package com.example.skladdo.dto;

import jakarta.validation.constraints.NotNull;

/**
 * The one setting a client company controls on an established connection: whether the warehouse
 * operator's staff see monetary values in its data.
 */
public record UpdateConnectionRequest(
        @NotNull Boolean canSeePrices
) {
}
