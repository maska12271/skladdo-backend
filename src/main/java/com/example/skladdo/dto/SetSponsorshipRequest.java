package com.example.skladdo.dto;

import jakarta.validation.constraints.Min;

/**
 * Granting or extending a company's free period.
 *
 * <p>Expressed in days from now rather than as a date so the two ways of saying it cannot disagree, and
 * so "another 30 days" is one number rather than a calculation the operator has to do. Clearing a
 * sponsorship is a separate DELETE, not {@code days = 0} - ending someone's free access should not be
 * something a mistyped number can do.</p>
 *
 * @param days      length of the free period from today
 * @param note      why it was granted; the operator's own record, never shown to the company
 * @param fromToday when true the period restarts today; otherwise it extends an existing one from where
 *                  it currently ends, which is what "give them another month" usually means
 */
public record SetSponsorshipRequest(
        @Min(1) int days,
        String note,
        Boolean fromToday
) {
}
