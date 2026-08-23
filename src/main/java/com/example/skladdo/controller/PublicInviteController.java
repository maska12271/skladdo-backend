package com.example.skladdo.controller;

import com.example.skladdo.dto.PublicInviteDto;
import com.example.skladdo.service.InviteLinkService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets the signup page look up an invite code before anyone has an account, so it can preselect the terms
 * and tell the visitor what they are getting.
 *
 * <p>Mounted under {@code /api/public/**}, which {@code SecurityConfig} permits without a JWT. It answers
 * {@code valid = false} for anything that would not work and never says why - see {@link PublicInviteDto}
 * for what is deliberately withheld. Nothing here grants anything: the terms are applied at signup by
 * re-reading the link, so a tampered response buys nothing.</p>
 */
@RestController
@RequestMapping("/api/public/invite")
@Tag(name = "Invite links")
public class PublicInviteController {

    private final InviteLinkService inviteLinkService;

    public PublicInviteController(InviteLinkService inviteLinkService) {
        this.inviteLinkService = inviteLinkService;
    }

    /** The terms behind a code, or {@code valid = false}. Always 200 - an unknown code is not an error. */
    @GetMapping
    public PublicInviteDto describe(@RequestParam String code) {
        return inviteLinkService.describe(code);
    }
}
