package com.example.tenderapp.dto;

import com.example.tenderapp.model.OrderStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateStatusRequest(@NotNull OrderStatus status) {
}
