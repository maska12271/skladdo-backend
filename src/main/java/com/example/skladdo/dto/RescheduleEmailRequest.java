package com.example.skladdo.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/** New time for a queued send. The only thing a scheduled email can be edited to change. */
public record RescheduleEmailRequest(@NotNull Instant scheduledAt) {
}
