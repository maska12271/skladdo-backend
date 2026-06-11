package com.example.tenderapp.dto;

import com.example.tenderapp.model.Role;
import com.example.tenderapp.model.User;

/**
 * Public representation of a user account. Never exposes the password hash.
 */
public record UserDto(
        Long id,
        String email,
        String fullName,
        Role role,
        Long companyId,
        String companyName,
        Boolean active,
        Boolean archived
) {
    public static UserDto from(User user) {
        return new UserDto(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                user.getCompany() != null ? user.getCompany().getId() : null,
                user.getCompany() != null ? user.getCompany().getName() : null,
                user.getActive(),
                user.getArchived()
        );
    }
}
