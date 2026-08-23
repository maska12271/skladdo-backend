package com.example.skladdo.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateWarehouseRequest(
        @NotBlank String name,
        String address,
        Boolean active
) {}
