package com.example.kladdo.service;

import com.example.kladdo.dto.ModulePermissionDto;
import com.example.kladdo.model.DefaultUserPermission;
import com.example.kladdo.model.PermissionModule;
import com.example.kladdo.model.Role;
import com.example.kladdo.model.User;
import com.example.kladdo.model.UserPermission;
import com.example.kladdo.repository.DefaultUserPermissionRepository;
import com.example.kladdo.repository.UserPermissionRepository;
import com.example.kladdo.security.CustomUserDetails;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Central authorization for fine-grained, per-module access. Registered as the Spring bean
 * {@code perm} so controllers can guard endpoints declaratively, e.g.
 * {@code @PreAuthorize("@perm.canDelete(authentication, 'PRODUCTS')")}.
 *
 * <p>Owners and administrators bypass every check. Regular users are governed by their
 * {@link UserPermission} rows (a missing row means no access).</p>
 */
@Service("perm")
public class PermissionService {

    private final UserPermissionRepository permissionRepository;
    private final DefaultUserPermissionRepository defaultPermissionRepository;

    public PermissionService(UserPermissionRepository permissionRepository,
                             DefaultUserPermissionRepository defaultPermissionRepository) {
        this.permissionRepository = permissionRepository;
        this.defaultPermissionRepository = defaultPermissionRepository;
    }

    // ---------------------------------------------------------------------------------------------
    // @PreAuthorize entry points
    // ---------------------------------------------------------------------------------------------

    public boolean canView(Authentication authentication, String module) {
        return check(authentication, module, UserPermission::isCanView);
    }

    public boolean canCreate(Authentication authentication, String module) {
        return check(authentication, module, UserPermission::isCanCreate);
    }

    public boolean canEdit(Authentication authentication, String module) {
        return check(authentication, module, UserPermission::isCanEdit);
    }

    public boolean canDelete(Authentication authentication, String module) {
        return check(authentication, module, UserPermission::isCanDelete);
    }

    /**
     * Read access to a reference module (products, manufacturers, categories, clients). Granted when
     * the user can view it directly <em>or</em> when they hold a create/edit permission elsewhere that
     * requires picking from it. This is what lets a user who can manage orders but not browse the
     * products page still load the product picker while building an order.
     *
     * <p>Dependencies:
     * <p>"Involved with" a module means holding any permission on it - viewing a module's page also
     * loads the reference data its filters and forms depend on, so view-only access is enough.
     * <ul>
     *     <li>PRODUCTS  - used by purchase &amp; sales order pages</li>
     *     <li>MANUFACTURERS - used by the products page and purchase order pages</li>
     *     <li>CLIENTS   - used by the sales order and tender pages</li>
     *     <li>CATEGORIES - used by the products page</li>
     * </ul>
     */
    public boolean canReadReference(Authentication authentication, String module) {
        if (isManager(authentication)) {
            return true;
        }
        PermissionModule target = PermissionModule.valueOf(module);
        Long userId = userId(authentication);
        List<UserPermission> permissions = permissionRepository.findByUserId(userId);

        if (permissions.stream().anyMatch(p -> p.getModule() == target && p.isCanView())) {
            return true;
        }

        boolean usesProducts = involvedIn(permissions, PermissionModule.PURCHASE_ORDERS)
                || involvedIn(permissions, PermissionModule.SALES_ORDERS);
        boolean usesManufacturers = involvedIn(permissions, PermissionModule.PRODUCTS)
                || involvedIn(permissions, PermissionModule.PURCHASE_ORDERS);
        boolean usesCategories = involvedIn(permissions, PermissionModule.PRODUCTS);
        // Manufacturer categories (what a manufacturer produces) are read on the manufacturers page to
        // populate the filter and the edit-form picker.
        boolean usesPartnerCategories = involvedIn(permissions, PermissionModule.MANUFACTURERS);
        boolean usesClients = involvedIn(permissions, PermissionModule.SALES_ORDERS)
                || involvedIn(permissions, PermissionModule.TENDERS);
        // Warehouse list is needed to pick a warehouse when creating/editing any order.
        boolean usesWarehouses = involvedIn(permissions, PermissionModule.PURCHASE_ORDERS)
                || involvedIn(permissions, PermissionModule.SALES_ORDERS);

        return switch (target) {
            case PRODUCTS -> usesProducts;
            case MANUFACTURERS -> usesManufacturers;
            case CLIENTS -> usesClients;
            case CATEGORIES -> usesCategories;
            case PARTNER_CATEGORIES -> usesPartnerCategories;
            case WAREHOUSES -> usesWarehouses;
            default -> false;
        };
    }

    // ---------------------------------------------------------------------------------------------
    // DTO / admin helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Complete permission map for a user, suitable for the login / {@code /me} response. Managers get
     * full access to every module; regular users get their stored flags (no-access where unset).
     */
    public Map<PermissionModule, ModulePermissionDto> permissionMapFor(User user) {
        Map<PermissionModule, ModulePermissionDto> result = new EnumMap<>(PermissionModule.class);
        if (isManagerRole(user.getRole())) {
            for (PermissionModule module : PermissionModule.values()) {
                result.put(module, ModulePermissionDto.all(module));
            }
            return result;
        }

        Map<PermissionModule, UserPermission> stored = storedByModule(user.getId());
        for (PermissionModule module : PermissionModule.values()) {
            UserPermission permission = stored.get(module);
            result.put(module, permission != null ? ModulePermissionDto.from(permission) : ModulePermissionDto.none(module));
        }
        return result;
    }

    /** Flat list of every module's flags for a user, for the admin permission editor. */
    public List<ModulePermissionDto> permissionsFor(Long userId) {
        Map<PermissionModule, UserPermission> stored = storedByModule(userId);
        return java.util.Arrays.stream(PermissionModule.values())
                .map(module -> {
                    UserPermission permission = stored.get(module);
                    return permission != null ? ModulePermissionDto.from(permission) : ModulePermissionDto.none(module);
                })
                .toList();
    }

    /**
     * The built-in fallback access for a newly created {@link Role#USER} when the company has not
     * configured its own default template: able to view and work with every area (view + create +
     * edit) but not delete. User management stays role-gated and is never granted here.
     */
    public List<ModulePermissionDto> builtInDefaults() {
        return java.util.Arrays.stream(PermissionModule.values())
                .map(module -> new ModulePermissionDto(module, true, true, true, false))
                .toList();
    }

    /**
     * The company-wide default template applied to new users, for the settings editor. Returns the
     * stored {@link DefaultUserPermission} rows (filling unconfigured modules with no-access) or, when
     * the company has never set a template, the {@link #builtInDefaults() built-in defaults}.
     */
    @Transactional(readOnly = true)
    public List<ModulePermissionDto> defaultPermissionTemplate() {
        List<DefaultUserPermission> stored = defaultPermissionRepository.findAll();
        if (stored.isEmpty()) {
            return builtInDefaults();
        }
        Map<PermissionModule, DefaultUserPermission> byModule = new EnumMap<>(PermissionModule.class);
        for (DefaultUserPermission row : stored) {
            byModule.put(row.getModule(), row);
        }
        return java.util.Arrays.stream(PermissionModule.values())
                .map(module -> {
                    DefaultUserPermission row = byModule.get(module);
                    return row != null
                            ? new ModulePermissionDto(module, row.isCanView(), row.isCanCreate(), row.isCanEdit(), row.isCanDelete())
                            : ModulePermissionDto.none(module);
                })
                .toList();
    }

    /** Overwrites the company's default-permission template (modules with no grant are removed). */
    @Transactional
    public List<ModulePermissionDto> replaceDefaultPermissionTemplate(List<ModulePermissionDto> incoming) {
        Map<PermissionModule, ModulePermissionDto> desired = new EnumMap<>(PermissionModule.class);
        if (incoming != null) {
            for (ModulePermissionDto dto : incoming) {
                if (dto != null && dto.module() != null) {
                    desired.put(dto.module(), dto);
                }
            }
        }

        Map<PermissionModule, DefaultUserPermission> stored = new EnumMap<>(PermissionModule.class);
        for (DefaultUserPermission row : defaultPermissionRepository.findAll()) {
            stored.put(row.getModule(), row);
        }

        for (PermissionModule module : PermissionModule.values()) {
            ModulePermissionDto dto = desired.get(module);
            boolean anyGranted = dto != null && (dto.canView() || dto.canCreate() || dto.canEdit() || dto.canDelete());
            DefaultUserPermission existing = stored.get(module);

            if (!anyGranted) {
                if (existing != null) {
                    defaultPermissionRepository.delete(existing);
                }
                continue;
            }

            DefaultUserPermission row = existing != null ? existing : newDefaultRow(module);
            row.setCanView(dto.canView());
            row.setCanCreate(dto.canCreate());
            row.setCanEdit(dto.canEdit());
            row.setCanDelete(dto.canDelete());
            defaultPermissionRepository.save(row);
        }
        return defaultPermissionTemplate();
    }

    /**
     * The access a newly created {@link Role#WAREHOUSE} account starts with: work the order flow
     * (view + change status) and keep stock (view products, post inventory adjustments). Order
     * fulfilment and stock-taking without the broader catalogue/admin reach a regular user gets.
     */
    public List<ModulePermissionDto> warehouseDefaults() {
        return List.of(
                new ModulePermissionDto(PermissionModule.PURCHASE_ORDERS, true, false, true, false),
                new ModulePermissionDto(PermissionModule.SALES_ORDERS, true, false, true, false),
                new ModulePermissionDto(PermissionModule.PRODUCTS, true, false, false, false),
                new ModulePermissionDto(PermissionModule.INVENTORY, true, true, false, false),
                new ModulePermissionDto(PermissionModule.WAREHOUSES, true, false, false, false)
        );
    }

    /**
     * Grants a newly created restricted account its starting permissions: warehouse accounts get the
     * {@link #warehouseDefaults() warehouse defaults}, regular users get the company
     * {@link #defaultPermissionTemplate() default template}.
     */
    @Transactional
    public void applyDefaultPermissions(User user) {
        List<ModulePermissionDto> template = user.getRole() == Role.WAREHOUSE
                ? warehouseDefaults()
                : defaultPermissionTemplate();
        replacePermissions(user, template);
    }

    /** Replaces all of a user's module permissions with the supplied set (full overwrite). */
    @Transactional
    public void replacePermissions(User user, List<ModulePermissionDto> incoming) {
        Map<PermissionModule, ModulePermissionDto> desired = new EnumMap<>(PermissionModule.class);
        if (incoming != null) {
            for (ModulePermissionDto dto : incoming) {
                if (dto != null && dto.module() != null) {
                    desired.put(dto.module(), dto);
                }
            }
        }

        Map<PermissionModule, UserPermission> stored = storedByModule(user.getId());

        for (PermissionModule module : PermissionModule.values()) {
            ModulePermissionDto dto = desired.get(module);
            boolean anyGranted = dto != null && (dto.canView() || dto.canCreate() || dto.canEdit() || dto.canDelete());
            UserPermission existing = stored.get(module);

            if (!anyGranted) {
                if (existing != null) {
                    permissionRepository.delete(existing);
                }
                continue;
            }

            UserPermission row = existing != null ? existing : newRow(user, module);
            row.setCanView(dto.canView());
            row.setCanCreate(dto.canCreate());
            row.setCanEdit(dto.canEdit());
            row.setCanDelete(dto.canDelete());
            permissionRepository.save(row);
        }
    }

    /** Removes all permission rows for a user (used when the account is deleted). */
    @Transactional
    public void clearPermissions(Long userId) {
        permissionRepository.deleteByUserId(userId);
    }

    // ---------------------------------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------------------------------

    private boolean check(Authentication authentication, String module, java.util.function.Predicate<UserPermission> flag) {
        if (isManager(authentication)) {
            return true;
        }
        PermissionModule target = PermissionModule.valueOf(module);
        return permissionRepository.findByUserIdAndModule(userId(authentication), target)
                .map(flag::test)
                .orElse(false);
    }

    /** True if the user holds any permission at all on the given module. */
    private boolean involvedIn(List<UserPermission> permissions, PermissionModule module) {
        return permissions.stream().anyMatch(p -> p.getModule() == module
                && (p.isCanView() || p.isCanCreate() || p.isCanEdit() || p.isCanDelete()));
    }

    private Map<PermissionModule, UserPermission> storedByModule(Long userId) {
        Map<PermissionModule, UserPermission> map = new EnumMap<>(PermissionModule.class);
        for (UserPermission permission : permissionRepository.findByUserId(userId)) {
            map.put(permission.getModule(), permission);
        }
        return map;
    }

    private UserPermission newRow(User user, PermissionModule module) {
        UserPermission row = new UserPermission();
        row.setUser(user);
        row.setModule(module);
        return row;
    }

    private DefaultUserPermission newDefaultRow(PermissionModule module) {
        DefaultUserPermission row = new DefaultUserPermission();
        row.setModule(module);
        return row;
    }

    private boolean isManager(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof CustomUserDetails details) {
            return isManagerRole(details.getRole());
        }
        return false;
    }

    private boolean isManagerRole(Role role) {
        return role == Role.OWNER || role == Role.ADMINISTRATOR;
    }

    private Long userId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof CustomUserDetails details) {
            return details.getId();
        }
        throw new IllegalStateException("No authenticated user in the security context");
    }
}
