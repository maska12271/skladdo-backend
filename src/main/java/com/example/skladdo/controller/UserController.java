package com.example.skladdo.controller;

import com.example.skladdo.dto.CreateUserRequest;
import com.example.skladdo.dto.CreatedUserResponse;
import com.example.skladdo.dto.ModulePermissionDto;
import com.example.skladdo.dto.SetupLinkResponse;
import com.example.skladdo.dto.UpdatePermissionsRequest;
import com.example.skladdo.dto.UpdateUserRequest;
import com.example.skladdo.dto.UpdateUserWarehousesRequest;
import com.example.skladdo.dto.UserDetailsDto;
import com.example.skladdo.dto.UserDto;
import com.example.skladdo.service.UserAnalyticsService;
import com.example.skladdo.service.UserService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * User-account management. Restricted to owners and administrators; every operation is scoped to
 * the caller's company.
 */
@RestController
@RequestMapping("/api/users")
@Tag(name = "Users")
@PreAuthorize("hasAnyRole('OWNER', 'ADMINISTRATOR')")
public class UserController {

    private final UserService userService;
    private final UserAnalyticsService userAnalyticsService;

    public UserController(UserService userService, UserAnalyticsService userAnalyticsService) {
        this.userService = userService;
        this.userAnalyticsService = userAnalyticsService;
    }

    @GetMapping
    public List<UserDto> getAll() {
        return userService.findAllForCurrentCompany();
    }

    @GetMapping("/{id}")
    public UserDto getById(@PathVariable Long id) {
        return userService.findOne(id);
    }

    @GetMapping("/{id}/details")
    public UserDetailsDto getDetails(@PathVariable Long id) {
        return userAnalyticsService.getDetails(id);
    }

    @PostMapping
    public CreatedUserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return userService.create(request);
    }

    /**
     * (Re)sends the password setup/reset link to a user and returns the outcome. Used for the "resend
     * setup email" (pending user) and "send password reset link" (active user) actions.
     */
    @PostMapping("/{id}/setup-email")
    public SetupLinkResponse sendSetupEmail(@PathVariable Long id) {
        return userService.sendSetupEmail(id);
    }

    @PutMapping("/{id}")
    public UserDto update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
        return userService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        userService.delete(id);
    }

    @PutMapping("/{id}/archive")
    public UserDto archive(@PathVariable Long id) {
        return userService.setArchived(id, true);
    }

    @PutMapping("/{id}/unarchive")
    public UserDto unarchive(@PathVariable Long id) {
        return userService.setArchived(id, false);
    }

    @GetMapping("/{id}/permissions")
    public List<ModulePermissionDto> getPermissions(@PathVariable Long id) {
        return userService.getPermissions(id);
    }

    @PutMapping("/{id}/permissions")
    public List<ModulePermissionDto> updatePermissions(@PathVariable Long id,
                                                       @Valid @RequestBody UpdatePermissionsRequest request) {
        return userService.updatePermissions(id, request);
    }

    @PutMapping("/{id}/warehouses")
    public List<Long> updateWarehouses(@PathVariable Long id,
                                       @Valid @RequestBody UpdateUserWarehousesRequest request) {
        return userService.updateWarehouses(id, request);
    }
}
