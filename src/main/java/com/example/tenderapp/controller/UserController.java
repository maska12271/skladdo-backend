package com.example.tenderapp.controller;

import com.example.tenderapp.dto.CreateUserRequest;
import com.example.tenderapp.dto.UpdateUserRequest;
import com.example.tenderapp.dto.UserDto;
import com.example.tenderapp.service.UserService;
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

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public List<UserDto> getAll() {
        return userService.findAllForCurrentCompany();
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
}
