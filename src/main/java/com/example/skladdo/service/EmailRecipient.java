package com.example.skladdo.service;

import com.example.skladdo.model.Client;
import com.example.skladdo.model.EmailRecipientType;
import com.example.skladdo.model.Manufacturer;

/**
 * The parts of a client or a manufacturer that sending an email actually needs: where to write to, and
 * the values the template tokens are substituted with.
 *
 * <p>The one seam that keeps the send path from branching on entity type. Both partners carry the same
 * shape of contact detail, so everything downstream of {@link #of} - rendering, addressing, the audit
 * snapshot - is written once against this record rather than twice against two entities.</p>
 */
public record EmailRecipient(
        EmailRecipientType type,
        Long id,
        String name,
        String address,
        String email,
        String phone,
        String country
) {
    public static EmailRecipient of(Manufacturer m) {
        return new EmailRecipient(EmailRecipientType.MANUFACTURER, m.getId(), m.getName(),
                m.getAddress(), m.getEmail(), m.getPhone(), m.getCountry());
    }

    public static EmailRecipient of(Client c) {
        return new EmailRecipient(EmailRecipientType.CLIENT, c.getId(), c.getName(),
                c.getAddress(), c.getEmail(), c.getPhone(), c.getCountry());
    }
}
