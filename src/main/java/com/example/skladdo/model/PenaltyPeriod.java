package com.example.skladdo.model;

/**
 * How a late-payment penalty accrues on an overdue invoice. Stored on {@link CompanySettings} so the
 * company can express its standard penalty terms; the actual penalty calculation is left to the
 * invoicing flow that consumes these settings.
 */
public enum PenaltyPeriod {
    /** A single penalty applied once when the invoice becomes overdue. */
    ONE_TIME,
    /** The penalty percentage accrues for every day the invoice is overdue. */
    DAILY,
    /** The penalty percentage accrues for every week the invoice is overdue. */
    WEEKLY,
    /** The penalty percentage accrues for every month the invoice is overdue. */
    MONTHLY
}
