package com.example.kladdo.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Everything the "Plan &amp; Billing" settings tab needs in one payload: the company's current
 * subscription and billing dates, its usage against the current caps, the catalogue of plans it can
 * switch to, and the add-on store. A limit of {@code -1} means unlimited.
 */
public record SubscriptionViewDto(
        String plan,
        String status,
        Instant trialEndsAt,
        /** End of the current period; the next-payment date, or the access-until date when cancelling. */
        Instant currentPeriodEnd,
        boolean cancelAtPeriodEnd,
        BigDecimal monthlyPrice,
        String currency,
        List<UsageItem> usage,
        List<PlanOption> plans,
        List<AddonOption> addons
) {

    /** One metered resource: how many the company uses vs. the current plan's cap ({@code -1} = unlimited). */
    public record UsageItem(String resource, long used, int limit) {
    }

    /** A plan the company can switch to, with its caps and price. */
    public record PlanOption(String plan, BigDecimal monthlyPrice, int maxUsers, int maxManufacturers, int maxProducts) {
    }

    /** An add-on in the store, with whether the company currently has it and any pending cancellation. */
    public record AddonOption(String addon, BigDecimal monthlyPrice, boolean active,
                              boolean cancelAtPeriodEnd, Instant currentPeriodEnd) {
    }
}
