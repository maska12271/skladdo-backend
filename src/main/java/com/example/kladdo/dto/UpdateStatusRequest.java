package com.example.kladdo.dto;

import com.example.kladdo.model.OrderStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateStatusRequest(@NotNull OrderStatus status) {
}
