package com.example.skladdo.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record UpdateUserWarehousesRequest(@NotNull List<Long> warehouseIds) {}
