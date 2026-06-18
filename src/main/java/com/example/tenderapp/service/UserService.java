package com.example.tenderapp.service;

import com.example.tenderapp.dto.CreateUserRequest;
import com.example.tenderapp.dto.ModulePermissionDto;
import com.example.tenderapp.dto.UpdatePermissionsRequest;
import com.example.tenderapp.dto.UpdateUserRequest;
import com.example.tenderapp.dto.UserDto;
import com.example.tenderapp.exception.ForbiddenException;
import com.example.tenderapp.exception.ResourceNotFoundException;
import com.example.tenderapp.model.Company;
import com.example.tenderapp.model.PermissionModule;
import com.example.tenderapp.model.Role;
import com.example.tenderapp.model.User;
import com.example.tenderapp.repository.CompanyRepository;
import com.example.tenderapp.repository.UserRepository;
import com.example.tenderapp.security.SecurityUtil;
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

    public UserService(UserRepository userRepository,
                       CompanyRepository companyRepository,
                       PasswordEncoder passwordEncoder,
                       PermissionService permissionService) {
        this.userRepository = userRepository;
        this.companyRepository = companyRepository;
        this.passwordEncoder = passwordEncoder;
        this.permissionService = permissionService;
    }

    public List<UserDto> findAllForCurrentCompany() {
        return userRepository.findByCompanyIdOrderByIdDesc(SecurityUtil.currentCompanyId())
                .stream()
                .map(UserDto::from)
                .toList();
    }

    /** Single user, scoped to the caller's company. Used by the user profile page. */
    public UserDto findOne(Long id) {
        return UserDto.from(requireSameCompany(id));
    }

    public UserDto create(CreateUserRequest request) {
        if (request.role() == Role.OWNER) {
            throw new ForbiddenException("Owner accounts cannot be created through user management");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("An account with this email already exists");
        }

        Long companyId = SecurityUtil.currentCompanyId();
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found with id: " + companyId));

        User user = new User();
        user.setEmail(request.email());
        user.setFullName(request.fullName());
        user.setRole(request.role());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setCompany(company);
        user.setActive(true);
        user.setArchived(false);

        User saved = userRepository.save(user);
        // A brand-new regular user starts with the company default access instead of nothing.
        if (saved.getRole() == Role.USER) {
            permissionService.applyDefaultPermissions(saved);
        }
        return UserDto.from(saved);
    }

    public UserDto update(Long id, UpdateUserRequest request) {
        User user = requireSameCompany(id);
        if (user.getRole() == Role.OWNER) {
            throw new ForbiddenException("The owner account cannot be modified");
        }
        if (request.role() == Role.OWNER) {
            throw new ForbiddenException("A user cannot be promoted to owner");
        }

        boolean wasUser = user.getRole() == Role.USER;

        user.setFullName(request.fullName());
        user.setRole(request.role());

        if (request.password() != null && !request.password().isBlank()) {
            if (request.password().length() < 6) {
                throw new IllegalArgumentException("Password must be at least 6 characters");
            }
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }

        User saved = userRepository.save(user);
        // Keep permission rows consistent with the role: a fresh demotion to USER gets the defaults,
        // a promotion to a manager role drops the now-ignored rows.
        if (request.role() == Role.USER && !wasUser) {
            permissionService.applyDefaultPermissions(saved);
        } else if (request.role() != Role.USER && wasUser) {
            permissionService.clearPermissions(saved.getId());
        }
        return UserDto.from(saved);
    }

    public void delete(Long id) {
        User user = requireSameCompany(id);
        guardNotSelf(user, "delete");
        if (user.getRole() == Role.OWNER) {
            throw new ForbiddenException("The owner account cannot be deleted");
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
        if (user.getRole() != Role.USER) {
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
        if (user.getRole() != Role.USER) {
            throw new ForbiddenException("Permissions can only be set for regular user accounts");
        }
        permissionService.replacePermissions(user, request.permissions());
        return permissionService.permissionsFor(user.getId());
    }

    public UserDto setArchived(Long id, boolean archived) {
        User user = requireSameCompany(id);
        guardNotSelf(user, archived ? "archive" : "unarchive");
        if (user.getRole() == Role.OWNER) {
            throw new ForbiddenException("The owner account cannot be archived");
        }
        user.setArchived(archived);
        return UserDto.from(userRepository.save(user));
    }

    private User requireSameCompany(Long id) {
        return userRepository.findByIdAndCompanyId(id, SecurityUtil.currentCompanyId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
    }

    private void guardNotSelf(User user, String action) {
        if (user.getId().equals(SecurityUtil.currentUserId())) {
            throw new ForbiddenException("You cannot " + action + " your own account");
        }
    }
}
