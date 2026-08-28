package com.example.skladdo.controller;

import com.example.skladdo.dto.CreateUserInviteRequest;
import com.example.skladdo.dto.SendUserInviteRequest;
import com.example.skladdo.dto.SendUserInviteResult;
import com.example.skladdo.dto.UserInviteDto;
import com.example.skladdo.service.UserInviteService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Invitation links a company issues to bring someone on board. Restricted to owners and administrators,
 * like the rest of user management, and every operation is scoped to the caller's company.
 *
 * <p>The redemption half is unauthenticated and lives in {@link PublicUserInviteController} - the person
 * following a link has no account yet, which is the whole point.</p>
 */
@RestController
@RequestMapping("/api/user-invites")
@Tag(name = "User invitations")
@PreAuthorize("hasAnyRole('OWNER', 'ADMINISTRATOR')")
public class UserInviteController {

    private final UserInviteService userInviteService;

    public UserInviteController(UserInviteService userInviteService) {
        this.userInviteService = userInviteService;
    }

    @GetMapping
    public List<UserInviteDto> list() {
        return userInviteService.list();
    }

    /** Mints a link. The response carries the full URL, ready to copy or to email from the next call. */
    @PostMapping
    public UserInviteDto create(@Valid @RequestBody CreateUserInviteRequest request) {
        return userInviteService.create(request);
    }

    /**
     * Emails an existing link. Delivery only - it does not bind the invitation to that address.
     *
     * <p>Answers whether anything actually left: with no platform sender configured the send is a no-op,
     * and the caller needs to know that rather than being told it worked.</p>
     */
    @PostMapping("/{id}/send")
    public SendUserInviteResult send(@PathVariable Long id, @Valid @RequestBody SendUserInviteRequest request) {
        return userInviteService.send(id, request);
    }

    @PutMapping("/{id}/revoke")
    public UserInviteDto revoke(@PathVariable Long id) {
        return userInviteService.revoke(id);
    }
}
