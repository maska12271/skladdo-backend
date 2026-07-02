package com.example.kladdo.service;

import com.example.kladdo.dto.CreateUserRequest;
import com.example.kladdo.dto.ModulePermissionDto;
import com.example.kladdo.dto.UpdatePermissionsRequest;
import com.example.kladdo.dto.UpdateUserRequest;
import com.example.kladdo.dto.UpdateUserWarehousesRequest;
import com.example.kladdo.dto.UserDto;
import com.example.kladdo.exception.BadRequestException;
import com.example.kladdo.exception.ForbiddenException;
import com.example.kladdo.exception.ResourceNotFoundException;
import com.example.kladdo.model.Company;
import com.example.kladdo.model.PermissionModule;
import com.example.kladdo.model.Role;
import com.example.kladdo.model.User;
import com.example.kladdo.repository.CompanyRepository;
import com.example.kladdo.repository.UserRepository;
import com.example.kladdo.security.SecurityUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * Manages user accounts within the caller's company. All lookups are scoped to the current
 * company so administrators can never read or modify another company's users.
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final PasswordEncoder passwordEncoder;
    private final PermissionService permissionService;
    private final WarehouseService warehouseService;

    public UserService(UserRepository userRepository,
                       CompanyRepository companyRepository,
                       PasswordEncoder passwordEncoder,
                       PermissionService permissionService,
                       WarehouseService warehouseService) {
        this.userRepository = userRepository;
        this.companyRepository = companyRepository;
        this.passwordEncoder = passwordEncoder;
        this.permissionService = permissionService;
        this.warehouseService = warehouseService;
    }

    public List<UserDto> findAllForCurrentCompany() {
        return userRepository.findByCompanyIdOrderByIdDesc(SecurityUtil.currentCompanyId())
                .stream()
                .map(UserDto::from)
                .toList();
    }

    /** Single user, scoped to the caller's company. Includes warehouse assignment. */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public UserDto findOne(Long id) {
        User user = requireSameCompany(id);
        user.getWarehouses().size(); // init lazy collection
        return UserDto.from(user, null,
                user.getWarehouses().stream()
                        .map(w -> w.getId())
                        .sorted()
                        .toList());
    }

    public UserDto create(CreateUserRequest request) {
        if (request.role() == Role.OWNER) {
            throw new ForbiddenException("error.user.ownerCannotCreate");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new BadRequestException("error.user.emailExists");
        }

        Long companyId = SecurityUtil.currentCompanyId();
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found with id: " + companyId));

        User user = new User();
        user.setEmail(request.email());
        user.setFullName(request.fullName());
        user.setRole(request.role());
        user.setCanSeePrices(request.canSeePrices() == null ? Boolean.TRUE : request.canSeePrices());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setCompany(company);
        user.setActive(true);
        user.setArchived(false);

        User saved = userRepository.save(user);
        // A brand-new restricted account (regular user or warehouse) starts with its default access
        // instead of nothing.
        if (isRestricted(saved.getRole())) {
            permissionService.applyDefaultPermissions(saved);
        }
        return UserDto.from(saved);
    }

    public UserDto update(Long id, UpdateUserRequest request) {
        User user = requireSameCompany(id);
        if (user.getRole() == Role.OWNER) {
            throw new ForbiddenException("error.user.ownerCannotModify");
        }
        if (request.role() == Role.OWNER) {
            throw new ForbiddenException("error.user.cannotPromoteOwner");
        }

        boolean wasRestricted = isRestricted(user.getRole());

        user.setFullName(request.fullName());
        user.setRole(request.role());
        if (request.canSeePrices() != null) {
            user.setCanSeePrices(request.canSeePrices());
        }

        if (request.password() != null && !request.password().isBlank()) {
            if (request.password().length() < 6) {
                throw new BadRequestException("error.user.passwordTooShort");
            }
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }

        boolean nowRestricted = isRestricted(request.role());
        User saved = userRepository.save(user);
        // Keep permission rows consistent with the role: a fresh move into a restricted role (user or
        // warehouse) gets the defaults, a promotion to a manager role drops the now-ignored rows.
        // Switching between the two restricted roles keeps the existing rows untouched.
        if (nowRestricted && !wasRestricted) {
            permissionService.applyDefaultPermissions(saved);
        } else if (!nowRestricted && wasRestricted) {
            permissionService.clearPermissions(saved.getId());
        }
        return UserDto.from(saved);
    }

    public void delete(Long id) {
        User user = requireSameCompany(id);
        guardNotSelf(user, "error.user.cannotDeleteSelf");
        if (user.getRole() == Role.OWNER) {
            throw new ForbiddenException("error.user.ownerCannotDelete");
        }
        permissionService.clearPermissions(user.getId());
        userRepository.delete(user);
    }

    /**
     * Returns the full per-module permission set for a user. Owners and administrators are
     * unrestricted, so every module is reported as full access.
     */
    public List<ModulePermissionDto> getPermissions(Long id) {
        User user = requireSameCompany(id);
        if (!isRestricted(user.getRole())) {
            return Arrays.stream(PermissionModule.values()).map(ModulePermissionDto::all).toList();
        }
        return permissionService.permissionsFor(user.getId());
    }

    /**
     * Overwrites a user's module permissions. Only {@link Role#USER} accounts can be restricted -
     * owners and administrators are always full-access.
     */
    public List<ModulePermissionDto> updatePermissions(Long id, UpdatePermissionsRequest request) {
        User user = requireSameCompany(id);
        if (!isRestricted(user.getRole())) {
            throw new ForbiddenException("error.user.permissionsRestrictedRoles");
        }
        permissionService.replacePermissions(user, request.permissions());
        return permissionService.permissionsFor(user.getId());
    }

    public UserDto setArchived(Long id, boolean archived) {
        User user = requireSameCompany(id);
        guardNotSelf(user, archived ? "error.user.cannotArchiveSelf" : "error.user.cannotUnarchiveSelf");
        if (user.getRole() == Role.OWNER) {
            throw new ForbiddenException("error.user.ownerCannotArchive");
        }
        user.setArchived(archived);
        return UserDto.from(userRepository.save(user));
    }

    /** Replaces the set of warehouses assigned to a user. */
    public List<Long> updateWarehouses(Long id, UpdateUserWarehousesRequest request) {
        requireSameCompany(id); // scope check
        return warehouseService.setUserWarehouses(id, request.warehouseIds());
    }

    /** Restricted roles are governed by per-module permission rows (unlike full-access managers). */
    private static boolean isRestricted(Role role) {
        return role == Role.USER || role == Role.WAREHOUSE;
    }

    private User requireSameCompany(Long id) {
        return userRepository.findByIdAndCompanyId(id, SecurityUtil.currentCompanyId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
    }

    private void guardNotSelf(User user, String messageKey) {
        if (user.getId().equals(SecurityUtil.currentUserId())) {
            throw new ForbiddenException(messageKey);
        }
    }
}
