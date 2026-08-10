package com.example.kladdo.model;

import java.math.BigDecimal;

/**
 * The subscription tiers a company can be on. Each tier bundles the hard caps on core resources
 * ({@link #maxUsers}, {@link #maxManufacturers}, {@link #maxProducts}) and its monthly price. The
 * add-on features (emailing manufacturers, tenders) are sold separately - see {@link AddonType}.
 *
 * <p>There is no perpetual free tier a business can sit on: an ordinary company picks one of the paid
 * plans below at signup and is billed from its second month. The one exception is {@link #WAREHOUSE},
 * which is not a tier anyone chooses - see its own note.</p>
 *
 * <p>Limits are static per tier so there is no admin UI or migration needed to change a number: edit
 * the constant here. A limit of {@link #UNLIMITED} means "no cap". Prices are display-only for now -
 * billing is not wired to a payment provider yet (see {@code PlanService}).</p>
 */
public enum PlanType {

    // -1 == UNLIMITED (the named constant can't be forward-referenced from the constants above it).
    STARTER(new BigDecimal("29"), 5, 25, 250),
    BUSINESS(new BigDecimal("79"), 25, -1, -1),
    ENTERPRISE(new BigDecimal("199"), -1, -1, -1),

    /**
     * What every {@link CompanyType#WAREHOUSE} account is on: free, and not for sale. A warehouse account
     * owns no catalogue and no orders, so the product and manufacturer caps are meaningless to it and the
     * user cap is left open - it may connect to as many client companies as it likes. Charging logistics
     * providers is a later decision; until then this keeps the promise made at signup ("no payment") and
     * what the billing tab reports in agreement.
     *
     * <p>It is assigned from the account type, never picked: {@link #isSelectable()} is false, so it is
     * absent from the plan list the settings page offers and rejected by a plan change. Without that a
     * business could downgrade itself to a free plan.</p>
     */
    WAREHOUSE(BigDecimal.ZERO, -1, -1, -1);

    /** Sentinel used by the {@code max*} caps to mean "no limit". */
    public static final int UNLIMITED = -1;

    private final BigDecimal monthlyPrice;
    private final int maxUsers;
    private final int maxManufacturers;
    private final int maxProducts;

    PlanType(BigDecimal monthlyPrice, int maxUsers, int maxManufacturers, int maxProducts) {
        this.monthlyPrice = monthlyPrice;
        this.maxUsers = maxUsers;
        this.maxManufacturers = maxManufacturers;
        this.maxProducts = maxProducts;
    }

    public BigDecimal getMonthlyPrice() {
        return monthlyPrice;
    }

    public int getMaxUsers() {
        return maxUsers;
    }

    public int getMaxManufacturers() {
        return maxManufacturers;
    }

    public int getMaxProducts() {
        return maxProducts;
    }

    /**
     * Whether a company may put itself on this plan. False only for {@link #WAREHOUSE}, which follows from
     * the account type; everything else is a paid tier anyone can choose.
     */
    public boolean isSelectable() {
        return this != WAREHOUSE;
    }
}
