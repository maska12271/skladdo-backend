package com.example.kladdo.controller;

import com.example.kladdo.dto.CreateUserRequest;
import com.example.kladdo.dto.ModulePermissionDto;
import com.example.kladdo.dto.UpdatePermissionsRequest;
import com.example.kladdo.dto.UpdateUserRequest;
import com.example.kladdo.dto.UpdateUserWarehousesRequest;
import com.example.kladdo.dto.UserDetailsDto;
import com.example.kladdo.dto.UserDto;
import com.example.kladdo.service.UserAnalyticsService;
import com.example.kladdo.service.UserService;
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
    public UserDto create(@Valid @RequestBody CreateUserRequest request) {
        return userService.create(request);
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
