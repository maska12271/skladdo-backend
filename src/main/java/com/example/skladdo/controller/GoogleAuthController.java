package com.example.skladdo.controller;

import com.example.skladdo.dto.GoogleLoginRequest;
import com.example.skladdo.dto.GoogleRegisterRequest;
import com.example.skladdo.dto.LoginResponse;
import com.example.skladdo.exception.LocalizedException;
import com.example.skladdo.security.ExternalIdentity;
import com.example.skladdo.security.GoogleIdTokenVerifier;
import com.example.skladdo.security.LoginRateLimiter;
import com.example.skladdo.service.AuthService;
import com.example.skladdo.service.GoogleAuthService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign in and sign up with Google. Mounted under {@code /api/public/**}, which {@code SecurityConfig}
 * already permits without a JWT - there is nothing to authenticate with yet, which is the point.
 *
 * <p>Both endpoints return the same {@link LoginResponse} as every other way into the application, so the
 * client puts the caller into a session the one way it always has.</p>
 */
@RestController
@RequestMapping("/api/public/auth/google")
@Tag(name = "Authentication")
public class GoogleAuthController {

    private final GoogleIdTokenVerifier verifier;
    private final GoogleAuthService googleAuthService;
    private final AuthService authService;
    private final LoginRateLimiter rateLimiter;

    public GoogleAuthController(GoogleIdTokenVerifier verifier,
                                GoogleAuthService googleAuthService,
                                AuthService authService,
                                LoginRateLimiter rateLimiter) {
        this.verifier = verifier;
        this.googleAuthService = googleAuthService;
        this.authService = authService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Exchanges a verified Google ID token for a session.
     *
     * <p>Rate limited on the address inside the token, the same as the password endpoint. A signed token
     * cannot be guessed, so this is not protecting a credential - it caps how fast one caller can probe the
     * account states this endpoint distinguishes (no account, linked elsewhere, company suspended).</p>
     */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody GoogleLoginRequest request, HttpServletRequest http) {
        ExternalIdentity identity = verifier.verify(request.idToken());
        String ip = LoginRateLimiter.clientIp(http);
        rateLimiter.checkAllowed(ip, identity.email());
        try {
            LoginResponse response = googleAuthService.login(identity);
            rateLimiter.recordSuccess(identity.email());
            // Outside the service call and non-throwing, for the reason AuthController.login gives: this is
            // bookkeeping for the admin panel's activity figures and must never cost anyone their sign-in.
            authService.recordLogin(response.user().id());
            return withAddons(response);
        } catch (LocalizedException e) {
            rateLimiter.recordFailure(ip, identity.email());
            throw e;
        }
    }

    /** Creates a new company whose owner signs in with Google instead of a password. */
    @PostMapping("/register")
    public LoginResponse register(@Valid @RequestBody GoogleRegisterRequest request) {
        ExternalIdentity identity = verifier.verify(request.idToken());
        return withAddons(googleAuthService.register(identity, request));
    }

    /**
     * Attaches the company's add-ons to the profile. Done out here rather than inside the service because
     * the lookup binds its own tenant - see {@link AuthService#addonsOf}. A signup can switch add-ons on,
     * so without this the new owner lands on a dashboard missing the features they just paid for.
     */
    private LoginResponse withAddons(LoginResponse response) {
        return response.withAddons(authService.addonsOf(response.user().companyId()));
    }
}
