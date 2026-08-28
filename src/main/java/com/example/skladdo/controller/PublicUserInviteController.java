package com.example.skladdo.controller;

import com.example.skladdo.dto.AcceptUserInviteRequest;
import com.example.skladdo.dto.PublicUserInviteDto;
import com.example.skladdo.service.UserInviteService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Where an invited colleague sets themselves up, before they have an account of any kind.
 *
 * <p>Mounted under {@code /api/public/**}, which {@code SecurityConfig} permits without a JWT. The
 * lookup answers {@code valid = false} for anything unusable and never says why - see
 * {@link PublicUserInviteDto}. The token is the only credential either endpoint accepts, and every term
 * of the invitation (which company, which role, what access) is re-read from the stored row, so nothing
 * a caller sends can widen what they get.</p>
 */
@RestController
@RequestMapping("/api/public/user-invite")
@Tag(name = "User invitations")
public class PublicUserInviteController {

    private final UserInviteService userInviteService;

    public PublicUserInviteController(UserInviteService userInviteService) {
        this.userInviteService = userInviteService;
    }

    /** Who is inviting, and until when. Always 200 - an unknown token is not an error. */
    @GetMapping
    public PublicUserInviteDto describe(@RequestParam String token) {
        return userInviteService.describe(token);
    }

    /**
     * Creates the account. Returns the company name so the page can confirm what was joined without
     * having to trust the value it looked up earlier.
     */
    @PostMapping
    public Map<String, String> accept(@Valid @RequestBody AcceptUserInviteRequest request) {
        return Map.of("companyName", userInviteService.accept(request));
    }
}
