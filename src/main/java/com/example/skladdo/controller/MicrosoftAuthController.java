package com.example.skladdo.controller;

import com.example.skladdo.dto.LoginResponse;
import com.example.skladdo.dto.MicrosoftLoginRequest;
import com.example.skladdo.dto.MicrosoftRegisterRequest;
import com.example.skladdo.exception.LocalizedException;
import com.example.skladdo.security.ExternalIdentity;
import com.example.skladdo.security.LoginRateLimiter;
import com.example.skladdo.security.MicrosoftIdTokenVerifier;
import com.example.skladdo.service.AuthService;
import com.example.skladdo.service.MicrosoftAuthService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign in and sign up with Microsoft. The counterpart to {@link GoogleAuthController} - same shape, same
 * {@code /api/public/**} placement - see {@link com.example.skladdo.service.MicrosoftAuthService} for the
 * one behavioural difference (login never links to an existing account by email).
 */
@RestController
@RequestMapping("/api/public/auth/microsoft")
@Tag(name = "Authentication")
public class MicrosoftAuthController {

    private final MicrosoftIdTokenVerifier verifier;
    private final MicrosoftAuthService microsoftAuthService;
    private final AuthService authService;
    private final LoginRateLimiter rateLimiter;

    public MicrosoftAuthController(MicrosoftIdTokenVerifier verifier,
                                   MicrosoftAuthService microsoftAuthService,
                                   AuthService authService,
                                   LoginRateLimiter rateLimiter) {
        this.verifier = verifier;
        this.microsoftAuthService = microsoftAuthService;
        this.authService = authService;
        this.rateLimiter = rateLimiter;
    }

    /** Exchanges a verified Microsoft ID token for a session. Rate limited exactly as the Google endpoint is. */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody MicrosoftLoginRequest request, HttpServletRequest http) {
        ExternalIdentity identity = verifier.verify(request.idToken());
        String ip = LoginRateLimiter.clientIp(http);
        rateLimiter.checkAllowed(ip, identity.email());
        try {
            LoginResponse response = microsoftAuthService.login(identity);
            rateLimiter.recordSuccess(identity.email());
            authService.recordLogin(response.user().id());
            return withAddons(response);
        } catch (LocalizedException e) {
            rateLimiter.recordFailure(ip, identity.email());
            throw e;
        }
    }

    /** Creates a new company whose owner signs in with Microsoft instead of a password. */
    @PostMapping("/register")
    public LoginResponse register(@Valid @RequestBody MicrosoftRegisterRequest request) {
        ExternalIdentity identity = verifier.verify(request.idToken());
        return withAddons(microsoftAuthService.register(identity, request));
    }

    /** See {@link GoogleAuthController#withAddons} - identical reasoning. */
    private LoginResponse withAddons(LoginResponse response) {
        return response.withAddons(authService.addonsOf(response.user().companyId()));
    }
}
