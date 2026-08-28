package com.example.skladdo.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** The address to email an existing invitation link to. Delivery only - it does not bind the link. */
public record SendUserInviteRequest(
        @NotBlank @Email String email
) {
}
