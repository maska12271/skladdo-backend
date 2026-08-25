package com.example.skladdo.controller;

import com.example.skladdo.dto.LoginResponse;
import com.example.skladdo.dto.RegisterRequest;
import com.example.skladdo.service.AuthService;
import com.example.skladdo.service.RegistrationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated self-service signup. Mounted under {@code /api/public/**}, which {@link
 * com.example.skladdo.config.SecurityConfig} permits without a JWT. Returns the same {@link LoginResponse}
 * as a login so the client can put the new owner straight into an authenticated session.
 */
@RestController
@RequestMapping("/api/public/register")
@Tag(name = "Registration")
public class PublicRegistrationController {

    private final RegistrationService registrationService;
    private final AuthService authService;

    public PublicRegistrationController(RegistrationService registrationService, AuthService authService) {
        this.registrationService = registrationService;
        this.authService = authService;
    }

    @PostMapping
    public LoginResponse register(@Valid @RequestBody RegisterRequest request) {
        LoginResponse response = registrationService.register(request);
        // A signup can switch add-ons on, so the profile it returns has to carry them - otherwise the new
        // owner lands on a dashboard missing the very features they just paid for, until the next reload.
        return response.withAddons(authService.addonsOf(response.user().companyId()));
    }
}
