package com.example.kladdo.controller;

import com.example.kladdo.dto.LoginRequest;
import com.example.kladdo.dto.LoginResponse;
import com.example.kladdo.dto.UserDto;
import com.example.kladdo.service.AuthService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public UserDto me() {
        return authService.currentUser();
    }
}
