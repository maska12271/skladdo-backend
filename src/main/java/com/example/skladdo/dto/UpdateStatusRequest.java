package com.example.skladdo.dto;

import com.example.skladdo.model.OrderStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateStatusRequest(@NotNull OrderStatus status) {
}
