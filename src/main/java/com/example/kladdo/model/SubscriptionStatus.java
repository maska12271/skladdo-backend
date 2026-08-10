package com.example.kladdo.model;

/**
 * Lifecycle state of a {@link CompanySubscription}. {@code cancelAtPeriodEnd} on the subscription is a
 * separate flag - an {@link #ACTIVE} or {@link #TRIALING} subscription can be pending cancellation.
 */
public enum SubscriptionStatus {

    /** In the one-month free trial window; not billed yet. Converts to {@link #ACTIVE} at the period end. */
    TRIALING,

    /** A paid, running subscription. */
    ACTIVE,

    /** A cancelled subscription that has reached its period end and lapsed; access should be treated as ended. */
    EXPIRED
}
