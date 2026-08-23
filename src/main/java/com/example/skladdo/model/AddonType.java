package com.example.skladdo.model;

import java.math.BigDecimal;

/**
 * À-la-carte features a company can switch on from the in-app add-on store, independently of its base
 * {@link PlanType}. Each carries its own monthly price. An add-on is unlocked while the company has an
 * active {@link CompanyAddon} row for it.
 *
 * <p>These deliberately mirror the {@link PermissionModule} of the same name: the add-on is the
 * company-level entitlement (does this company pay for the feature at all), while the permission stays
 * the per-user grant (may this user use it). Both must be satisfied.</p>
 */
public enum AddonType {

    MANUFACTURER_EMAILS(new BigDecimal("19")),
    TENDERS(new BigDecimal("15"));

    private final BigDecimal monthlyPrice;

    AddonType(BigDecimal monthlyPrice) {
        this.monthlyPrice = monthlyPrice;
    }

    public BigDecimal getMonthlyPrice() {
        return monthlyPrice;
    }
}
