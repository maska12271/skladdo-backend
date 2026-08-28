package com.example.skladdo.dto;

/**
 * Outcome of emailing an invitation link.
 *
 * <p>{@code emailSent} reports that the platform sender is configured and the message was handed to it -
 * not that it has arrived, which nothing here can know (the send is queued off the request thread). It is
 * {@code false} when {@code app.mail.platform.smtp-host} is blank, which is the default: without this the
 * page would cheerfully confirm a send that never happened, and the copyable link - the whole fallback -
 * would look like the lesser option rather than the only one that works.</p>
 */
public record SendUserInviteResult(
        boolean emailSent,
        UserInviteDto invite
) {
}
