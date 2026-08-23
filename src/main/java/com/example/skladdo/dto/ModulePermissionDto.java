package com.example.skladdo.dto;

import com.example.skladdo.model.PermissionModule;
import com.example.skladdo.model.UserPermission;

/**
 * Access flags for a single {@link PermissionModule}. Self-describing (carries its own module) so it
 * works both as a list element (admin permission editor) and as a value in the per-user permission
 * map returned on login.
 */
public record ModulePermissionDto(
        PermissionModule module,
        boolean canView,
        boolean canCreate,
        boolean canEdit,
        boolean canDelete
) {
    public static ModulePermissionDto from(UserPermission permission) {
        return new ModulePermissionDto(
                permission.getModule(),
                permission.isCanView(),
                permission.isCanCreate(),
                permission.isCanEdit(),
                permission.isCanDelete()
        );
    }

    /** A no-access entry for a module the user has no row for. */
    public static ModulePermissionDto none(PermissionModule module) {
        return new ModulePermissionDto(module, false, false, false, false);
    }

    /** A full-access entry (used for owners/administrators, who are never restricted). */
    public static ModulePermissionDto all(PermissionModule module) {
        return new ModulePermissionDto(module, true, true, true, true);
    }
}
