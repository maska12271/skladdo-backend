package com.example.skladdo.dto;

import java.time.Instant;

/**
 * The result of the operator provisioning a company by hand: the company itself, plus the owner's
 * password-setup link and whether it was emailed.
 *
 * <p>The link is returned even on success for the same reason it is on
 * {@link CreatedUserResponse}: a brand-new company has no SMTP configured, so the mail almost always
 * fails to send and the operator needs something to paste into a reply.</p>
 */
public record CreatedCompanyResponse(
        AdminCompanyDto company,
        boolean emailSent,
        String setupLink,
        Instant expiresAt
) {
}
