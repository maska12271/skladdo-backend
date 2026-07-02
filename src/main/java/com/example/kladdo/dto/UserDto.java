package com.example.kladdo.dto;

import com.example.kladdo.model.PermissionModule;
import com.example.kladdo.model.Role;
import com.example.kladdo.model.User;
import com.example.kladdo.model.Warehouse;

import java.util.List;
import java.util.Map;

/**
 * Public representation of a user account. Never exposes the password hash.
 *
 * <p>{@code permissions} is populated for the authenticated user (login / {@code /me}) so the client
 * can decide which pages and actions to show. It is left {@code null} in bulk listings to avoid an
 * N+1 lookup - the admin editor fetches a user's permissions on demand.</p>
 */
public record UserDto(
        Long id,
        String email,
        String fullName,
        Role role,
        Long companyId,
        String companyName,
        Boolean canSeePrices,
        Boolean active,
        Boolean archived,
        Map<PermissionModule, ModulePermissionDto> permissions,
        List<Long> warehouseIds
) {
    public static UserDto from(User user) {
        return from(user, null, null);
    }

    public static UserDto from(User user, Map<PermissionModule, ModulePermissionDto> permissions) {
        return from(user, permissions, null);
    }

    public static UserDto from(User user, Map<PermissionModule, ModulePermissionDto> permissions, List<Long> warehouseIds) {
        return new UserDto(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                user.getCompany() != null ? user.getCompany().getId() : null,
                user.getCompany() != null ? user.getCompany().getName() : null,
                user.getCanSeePrices(),
                user.getActive(),
                user.getArchived(),
                permissions,
                warehouseIds
        );
    }

    public static UserDto fromWithWarehouses(User user, Map<PermissionModule, ModulePermissionDto> permissions) {
        List<Long> warehouseIds = user.getWarehouses().stream()
                .map(Warehouse::getId)
                .sorted()
                .toList();
        return from(user, permissions, warehouseIds);
    }
}
