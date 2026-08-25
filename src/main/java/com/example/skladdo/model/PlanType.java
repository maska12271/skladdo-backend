package com.example.skladdo.model;

import java.math.BigDecimal;

/**
 * The subscription tiers a company can be on. A tier is its monthly price and one cap:
 * {@link #maxUsers}. The add-on features (emailing manufacturers, tenders) are sold separately - see
 * {@link AddonType}.
 *
 * <p>Seats are deliberately the <em>only</em> thing metered. Capping catalogue size punishes a company
 * for the work it does in the product rather than for the value it takes out of it, and it lands as a
 * wall in the middle of an import. What a tier sells is how many people can work in it.</p>
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
    STARTER(new BigDecimal("29"), 5),
    BUSINESS(new BigDecimal("79"), 25),
    ENTERPRISE(new BigDecimal("199"), -1),

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
    WAREHOUSE(BigDecimal.ZERO, -1),

    /**
     * What a {@link CompanyType#PLATFORM} company is on: free, unlimited and not for sale. Skladdo does not
     * bill itself. It exists so the operator's shell company never falls to the lazy Starter default and
     * finds its own five-seat cap applied to the people running the service - the same trap
     * {@link #WAREHOUSE} exists to avoid. Like it, {@link #isSelectable()} is false.
     */
    PLATFORM(BigDecimal.ZERO, -1);

    /** Sentinel used by the {@code max*} caps to mean "no limit". */
    public static final int UNLIMITED = -1;

    private final BigDecimal monthlyPrice;
    private final int maxUsers;

    PlanType(BigDecimal monthlyPrice, int maxUsers) {
        this.monthlyPrice = monthlyPrice;
        this.maxUsers = maxUsers;
    }

    public BigDecimal getMonthlyPrice() {
        return monthlyPrice;
    }

    public int getMaxUsers() {
        return maxUsers;
    }

    /**
     * Whether a company may put itself on this plan. False for the two free tiers that follow from the
     * account type ({@link #WAREHOUSE}, {@link #PLATFORM}); everything else is a paid tier anyone can
     * choose. Without this a business could downgrade itself onto a free plan.
     */
    public boolean isSelectable() {
        return this != WAREHOUSE && this != PLATFORM;
    }
}
