package com.example.tenderapp.controller;

import com.example.tenderapp.dto.LoginRequest;
import com.example.tenderapp.dto.LoginResponse;
import com.example.tenderapp.dto.UserDto;
import com.example.tenderapp.service.AuthService;
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
