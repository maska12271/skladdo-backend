package com.example.kladdo.dto;

import jakarta.validation.constraints.NotBlank;

/** The target plan for a plan change (a {@link com.example.kladdo.model.PlanType} name). */
public record ChangePlanRequest(@NotBlank String plan) {
}
