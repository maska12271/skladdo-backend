package com.example.kladdo.controller;

import com.example.kladdo.dto.LoginResponse;
import com.example.kladdo.dto.RegisterRequest;
import com.example.kladdo.service.RegistrationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated self-service signup. Mounted under {@code /api/public/**}, which {@link
 * com.example.kladdo.config.SecurityConfig} permits without a JWT. Returns the same {@link LoginResponse}
 * as a login so the client can put the new owner straight into an authenticated session.
 */
@RestController
@RequestMapping("/api/public/register")
@Tag(name = "Registration")
public class PublicRegistrationController {

    private final RegistrationService registrationService;

    public PublicRegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping
    public LoginResponse register(@Valid @RequestBody RegisterRequest request) {
        return registrationService.register(request);
    }
}
