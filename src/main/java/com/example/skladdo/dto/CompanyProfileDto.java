package com.example.skladdo.dto;

import com.example.skladdo.model.Company;
import com.example.skladdo.model.CompanyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The company's own identity - the fields that describe the business itself rather than how the app
 * behaves for it. Used by both {@code GET} and {@code PUT} on {@code /api/company}.
 *
 * <p>The rest of a company's details (address, VAT number, bank, logo) live on {@code CompanySettings}
 * because they are edited through the settings page and printed on invoices; only the identity fields
 * that belong to {@link Company} itself are here.</p>
 */
public record CompanyProfileDto(
        Long id,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 100) String registrationCode,
        /** Read-only: the account type is fixed at signup, so anything sent here is ignored. */
        CompanyType type
) {
    public static CompanyProfileDto from(Company company) {
        return new CompanyProfileDto(company.getId(), company.getName(), company.getRegistrationCode(),
                company.getType());
    }
}
