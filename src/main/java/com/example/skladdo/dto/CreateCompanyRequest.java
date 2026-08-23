package com.example.skladdo.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * The operator provisioning a company by hand, for a customer who was signed up over a call rather than
 * through the public form.
 *
 * <p>No password field, deliberately. The owner sets their own through the emailed link, exactly as an
 * invited user does - so this path creates no credential the operator has ever seen, and needs no
 * "temporary password" to be communicated and then forgotten about.</p>
 *
 * @param accountType BUSINESS or WAREHOUSE; blank means BUSINESS
 * @param plan        the paid tier to start on; ignored for a warehouse account, which is always free
 */
public record CreateCompanyRequest(
        @NotBlank String companyName,
        String registrationCode,
        @NotBlank String ownerName,
        @NotBlank @Email String ownerEmail,
        String accountType,
        String plan
) {
}
