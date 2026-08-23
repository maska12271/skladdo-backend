package com.example.skladdo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Replaces the full set of module permissions for a user. Modules omitted from {@code permissions}
 * are treated as no-access.
 */
public record UpdatePermissionsRequest(
        @NotNull @Valid List<ModulePermissionDto> permissions
) {
}
