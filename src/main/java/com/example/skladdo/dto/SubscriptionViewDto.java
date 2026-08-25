package com.example.skladdo.dto;

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
        List<AddonOption> addons,
        /**
         * End of a free period granted by the platform, or null when the company pays normally. Present
         * so the billing tab does not go on announcing a payment date to a customer who was told their
         * account is free - the company cannot change it, it is shown to them.
         */
        Instant freeUntil
) {

    /** One metered resource: how many the company uses vs. the current plan's cap ({@code -1} = unlimited). */
    public record UsageItem(String resource, long used, int limit) {
    }

    /** A plan the company can switch to, with its seat cap and price. Seats are all a tier meters. */
    public record PlanOption(String plan, BigDecimal monthlyPrice, int maxUsers) {
    }

    /** An add-on in the store, with whether the company currently has it and any pending cancellation. */
    public record AddonOption(String addon, BigDecimal monthlyPrice, boolean active,
                              boolean cancelAtPeriodEnd, Instant currentPeriodEnd) {
    }
}
