package com.example.skladdo.dto;

import jakarta.validation.constraints.NotBlank;

/** The target plan for a plan change (a {@link com.example.skladdo.model.PlanType} name). */
public record ChangePlanRequest(@NotBlank String plan) {
}
