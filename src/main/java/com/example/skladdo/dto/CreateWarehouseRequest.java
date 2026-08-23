package com.example.skladdo.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateWarehouseRequest(
        @NotBlank String name,
        String address
) {}
