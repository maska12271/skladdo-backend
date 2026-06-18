package com.example.tenderapp.dto;

import com.example.tenderapp.model.PermissionModule;
import com.example.tenderapp.model.Role;
import com.example.tenderapp.model.User;

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
        Boolean active,
        Boolean archived,
        Map<PermissionModule, ModulePermissionDto> permissions
) {
    public static UserDto from(User user) {
        return from(user, null);
    }

    public static UserDto from(User user, Map<PermissionModule, ModulePermissionDto> permissions) {
        return new UserDto(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                user.getCompany() != null ? user.getCompany().getId() : null,
                user.getCompany() != null ? user.getCompany().getName() : null,
                user.getActive(),
                user.getArchived(),
                permissions
        );
    }
}
