package com.example.skladdo.controller;

import com.example.skladdo.dto.ChangePasswordRequest;
import com.example.skladdo.dto.CompanyOptionDto;
import com.example.skladdo.dto.LoginRequest;
import com.example.skladdo.dto.LoginResponse;
import com.example.skladdo.dto.SwitchCompanyRequest;
import com.example.skladdo.dto.UpdateAvatarRequest;
import com.example.skladdo.dto.UpdateProfileRequest;
import com.example.skladdo.dto.UpdateSignatureRequest;
import com.example.skladdo.dto.UserDto;
import com.example.skladdo.exception.LocalizedException;
import com.example.skladdo.security.LoginRateLimiter;
import com.example.skladdo.service.AuthService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService authService;
    private final LoginRateLimiter rateLimiter;

    public AuthController(AuthService authService, LoginRateLimiter rateLimiter) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Attaches the company's add-ons to a session profile. Done here, outside any transaction, because the
     * lookup has to bind its own tenant - see {@link AuthService#addonsOf}. Every endpoint that hands back
     * "who am I" goes through one of these two, so a client never has to ask separately what the company
     * is entitled to.
     */
    private UserDto withAddons(UserDto user) {
        return user.withAddons(authService.addonsOf(user.companyId()));
    }

    private LoginResponse withAddons(LoginResponse response) {
        return response.withAddons(authService.addonsOf(response.user().companyId()));
    }

    /**
     * Rate limited per account and per source address — this endpoint distinguishes "no such account"
     * from "wrong password", so without a limit it would hand an attacker both a user-enumeration oracle
     * and unlimited guesses. The check runs before any password hashing so a locked-out caller is cheap
     * to turn away.
     */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        String ip = LoginRateLimiter.clientIp(http);
        rateLimiter.checkAllowed(ip, request.email());
        try {
            LoginResponse response = authService.login(request);
            rateLimiter.recordSuccess(request.email());
            // Stamped here rather than inside AuthService.login, which runs read-only. Bookkeeping for the
            // admin panel's activity figures - a failure to record it must never cost the user their login,
            // hence the separate, non-throwing call.
            authService.recordLogin(response.user().id());
            return withAddons(response);
        } catch (LocalizedException e) {
            // Every rejection path (unknown email, wrong password, setup still pending) counts: each one
            // confirms something about the address, so none of them may be probed freely.
            rateLimiter.recordFailure(ip, request.email());
            throw e;
        }
    }

    @GetMapping("/me")
    public UserDto me() {
        return withAddons(authService.currentUser());
    }

    /**
     * The companies this session may work in - the account's own plus any client company reachable through
     * an active warehouse connection. Always at least one entry, so the client can render a switcher only
     * when there is genuinely something to switch to.
     */
    @GetMapping("/companies")
    public List<CompanyOptionDto> companies() {
        return authService.availableCompanies();
    }

    /**
     * Swaps the session to another company the caller may work in, returning a fresh token and the profile
     * as seen inside that company. Passing the account's own company id switches back.
     */
    @PostMapping("/switch-company")
    public LoginResponse switchCompany(@Valid @RequestBody SwitchCompanyRequest request) {
        return withAddons(authService.switchCompany(request.companyId()));
    }

    /** Any signed-in user updates their own email signature (used on outgoing manufacturer emails). */
    @PutMapping("/me/signature")
    public UserDto updateSignature(@Valid @RequestBody UpdateSignatureRequest request) {
        return withAddons(authService.updateSignature(request.signature()));
    }

    /** Any signed-in user sets their own avatar: an uploaded picture, a preset icon, or neither. */
    @PutMapping("/me/avatar")
    public UserDto updateAvatar(@Valid @RequestBody UpdateAvatarRequest request) {
        return withAddons(authService.updateAvatar(
                request.avatarKey(), request.avatarIcon(), request.avatarColor()));
    }

    /** Any signed-in user updates their own display name and/or interface language. */
    @PutMapping("/me/profile")
    public UserDto updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return withAddons(authService.updateProfile(request.fullName(), request.language()));
    }

    /** Any signed-in user changes their own password after confirming their current one. */
    @PutMapping("/me/password")
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request.currentPassword(), request.newPassword());
    }
}
