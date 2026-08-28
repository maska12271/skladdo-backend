package com.example.skladdo.dto;

import com.example.skladdo.model.PartnerContact;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * One named person at a partner, in both directions: the response shape and the write shape are the same
 * three fields, so there is nothing here to keep in step.
 *
 * <p>{@code id} is server-assigned and ignored on write - which partner a contact belongs to comes from
 * the URL, never from the body, so a request cannot move a person to another company's partner.</p>
 */
public record PartnerContactDto(
        Long id,
        @NotBlank String name,
        String position,
        @Email String email
) {
    public static PartnerContactDto from(PartnerContact contact) {
        return new PartnerContactDto(contact.getId(), contact.getName(), contact.getPosition(), contact.getEmail());
    }

    /** A short "Name (Position)" label for pickers and order lines; the position is dropped when absent. */
    public static String label(PartnerContact contact) {
        if (contact.getPosition() == null || contact.getPosition().isBlank()) {
            return contact.getName();
        }
        return contact.getName() + " (" + contact.getPosition() + ")";
    }
}
