package com.example.skladdo.dto;

import com.example.skladdo.security.PasswordPolicy;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Public self-service signup payload: the new company's name, the owner's name / login email / chosen
 * password, and the paid {@code plan} the company is starting on. The owner sets their password here
 * (they are the person filling in the form), so — unlike an admin-invited user — no emailed setup link
 * is needed and they can sign in immediately.
 *
 * <p>Card details are collected on the client as a preview only (billing is not wired to a payment
 * provider yet), so they are intentionally not part of this payload — nothing is charged or stored.</p>
 */
public record RegisterRequest(
        @NotBlank String companyName,
        @NotBlank String fullName,
        @NotBlank @Email String email,
        @NotBlank @Size(min = PasswordPolicy.MIN_LENGTH) String password,
        /**
         * One of the paid tiers, required for a {@code BUSINESS} signup. Left out by a {@code WAREHOUSE}
         * signup, which is free and therefore never shown a plan step — {@code RegistrationService} decides
         * from {@link #accountType} rather than trusting this, so it is not {@code @NotBlank}.
         */
        String plan,
        /**
         * {@code BUSINESS} or {@code WAREHOUSE} - see {@link com.example.skladdo.model.CompanyType}. This is
         * the one signup answer that can never be changed later, so it is asked here rather than inferred.
         * Absent means {@code BUSINESS}, which is what an ordinary signup is.
         */
        String accountType,
        /**
         * An operator-issued invite code from {@code /register?invite=<code>}, or absent for an ordinary
         * signup. Only the code travels: its terms (plan, account type, free period) are read from the
         * stored link server-side, never from this request, or a visitor could grant themselves whatever
         * they liked by editing the query string. An unusable code is refused rather than ignored.
         */
        String inviteCode
) {
}
