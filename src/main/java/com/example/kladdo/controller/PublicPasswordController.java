package com.example.kladdo.controller;

import com.example.kladdo.dto.ForgotPasswordRequest;
import com.example.kladdo.dto.ForgotPasswordResponse;
import com.example.kladdo.dto.PasswordResetInfoResponse;
import com.example.kladdo.dto.ResetPasswordRequest;
import com.example.kladdo.exception.LocalizedException;
import com.example.kladdo.security.LoginRateLimiter;
import com.example.kladdo.service.PasswordResetService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * Unauthenticated password self-service: request a reset link, check a link's validity, and redeem it.
 * Mounted under {@code /api/public/**}, which {@code SecurityConfig} permits without a JWT.
 */
@RestController
@RequestMapping("/api/public/password")
@Tag(name = "Password reset")
public class PublicPasswordController {

    private final PasswordResetService passwordResetService;
    private final LoginRateLimiter rateLimiter;

    public PublicPasswordController(PasswordResetService passwordResetService, LoginRateLimiter rateLimiter) {
        this.passwordResetService = passwordResetService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Emails a reset link for a live account; 400 ({@code error.auth.noAccount}) if the email is unknown.
     *
     * <p>Rate limited on the same counters as login: this endpoint is the second user-enumeration oracle
     * (it answers whether an address is registered), and unlimited use would also let someone spam a real
     * user's inbox with reset mail.</p>
     */
    @PostMapping("/forgot")
    public ForgotPasswordResponse forgot(@Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest http) {
        String ip = LoginRateLimiter.clientIp(http);
        rateLimiter.checkAllowed(ip, request.email());
        try {
            // Only the boolean - never the link. An unauthenticated caller must not be handed the means to
            // take over the account they just named.
            return new ForgotPasswordResponse(passwordResetService.requestReset(request.email()));
        } catch (LocalizedException e) {
            rateLimiter.recordFailure(ip, request.email());
            throw e;
        }
    }

    /** Reports whether a link is still usable, so the reset page can greet the user or show an error. */
    @GetMapping("/reset")
    public PasswordResetInfoResponse validate(@RequestParam String token) {
        return passwordResetService.validate(token);
    }

    /** Sets the account's new password and consumes the token. */
    @PostMapping("/reset")
    public void reset(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.reset(request.token(), request.password());
    }
}
