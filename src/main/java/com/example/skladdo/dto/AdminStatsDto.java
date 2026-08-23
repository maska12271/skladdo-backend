package com.example.skladdo.dto;

import java.util.List;

/**
 * The platform admin dashboard's figures. Every count is a plain number rather than a rate or trend:
 * the operator reads these to decide who to contact, so an unambiguous count is worth more than a
 * derived metric whose definition has to be remembered.
 *
 * <p>Output-only, so primitive components are safe here - nothing deserializes this (see the Jackson 3
 * note on absent primitives in request records).</p>
 *
 * @param newCompanies      signups within the last 7 / 30 / 90 days, keyed by window
 * @param activeCompanies   companies with a sign-in within the last 30 / 90 days, keyed by window
 * @param overdueCompanies  paying companies whose billing period has lapsed - a follow-up signal, not a
 *                          restriction; nothing is blocked by it (there is no payment provider yet)
 */
public record AdminStatsDto(
        long totalCompanies,
        long businessCompanies,
        long warehouseCompanies,
        long suspendedCompanies,
        /** Companies currently inside a free period granted by the operator. */
        long sponsoredCompanies,
        long totalUsers,
        List<WindowCount> newCompanies,
        List<WindowCount> activeCompanies,
        long overdueCompanies,
        /** How many companies sit on each plan, largest first. Companies with no subscription row are omitted. */
        List<PlanCount> planMix,
        /** The handful of most recent signups, so the dashboard can name them without a second request. */
        List<AdminCompanyDto> recentSignups,
        /**
         * Free periods ending within the next week, soonest first. The whole point of requiring an end
         * date on a sponsorship is that somebody gets told before it runs out - this is that telling.
         */
        List<AdminCompanyDto> sponsorshipsEndingSoon
) {
    /** A count over a trailing window, e.g. {@code days = 30}. */
    public record WindowCount(int days, long count) {
    }

    public record PlanCount(String plan, long count) {
    }
}
