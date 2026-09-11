package com.example.skladdo.controller;

import com.example.skladdo.dto.AcceptGoogleUserInviteRequest;
import com.example.skladdo.dto.AcceptMicrosoftUserInviteRequest;
import com.example.skladdo.dto.AcceptUserInviteRequest;
import com.example.skladdo.dto.PublicUserInviteDto;
import com.example.skladdo.exception.BadRequestException;
import com.example.skladdo.security.ExternalIdentity;
import com.example.skladdo.security.GoogleIdTokenVerifier;
import com.example.skladdo.security.MicrosoftIdTokenVerifier;
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
    private final GoogleIdTokenVerifier googleVerifier;
    private final MicrosoftIdTokenVerifier microsoftVerifier;

    public PublicUserInviteController(UserInviteService userInviteService,
                                      GoogleIdTokenVerifier googleVerifier,
                                      MicrosoftIdTokenVerifier microsoftVerifier) {
        this.userInviteService = userInviteService;
        this.googleVerifier = googleVerifier;
        this.microsoftVerifier = microsoftVerifier;
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

    /**
     * The same, for an invitee who signs up with Google rather than choosing a password. The account it
     * creates has no password at all, so it can only ever be entered through the provider - which is the
     * point for a company that wants revoking the Google account to be the end of the matter.
     *
     * <p>The token is verified here, before the service sees it, so nothing downstream ever handles an
     * unverified claim about who this is.</p>
     */
    @PostMapping("/google")
    public Map<String, String> acceptWithGoogle(@Valid @RequestBody AcceptGoogleUserInviteRequest request) {
        ExternalIdentity identity = googleVerifier.verify(request.idToken());
        if (!identity.emailVerified()) {
            throw new BadRequestException("error.auth.google.emailUnverified");
        }
        return Map.of("companyName", userInviteService.acceptWithGoogle(
                request.token(), identity, request.birthDate(), request.avatarImage()));
    }

    /**
     * The same, for an invitee who signs up with Microsoft. No email-verified check here, unlike the
     * Google sibling above: this endpoint never looks an existing account up by email (the invitation
     * token is what admits someone), so Microsoft's weaker guarantee on that claim - see
     * {@link MicrosoftIdTokenVerifier} - has nothing to protect against here.
     */
    @PostMapping("/microsoft")
    public Map<String, String> acceptWithMicrosoft(@Valid @RequestBody AcceptMicrosoftUserInviteRequest request) {
        ExternalIdentity identity = microsoftVerifier.verify(request.idToken());
        return Map.of("companyName", userInviteService.acceptWithMicrosoft(
                request.token(), identity, request.birthDate(), request.avatarImage()));
    }
}
