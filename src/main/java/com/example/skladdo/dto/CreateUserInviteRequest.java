package com.example.skladdo.dto;

import com.example.skladdo.model.Role;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Mints an invitation link. The administrator decides only what the account will be allowed to do - the
 * person behind the link supplies everything about themselves.
 *
 * @param role         the role the new account is created with; never {@link Role#OWNER}
 * @param canSeePrices price visibility, meaningful for the restricted roles only
 * @param permissions  per-module overrides, or {@code null}/empty to use the company's default template
 */
public record CreateUserInviteRequest(
        @NotNull Role role,
        Boolean canSeePrices,
        List<ModulePermissionDto> permissions
) {
}
