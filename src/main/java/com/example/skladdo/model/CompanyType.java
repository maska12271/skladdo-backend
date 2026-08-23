package com.example.skladdo.model;

/**
 * What kind of account a {@link Company} is. Chosen at signup and <strong>immutable</strong> afterwards,
 * because it decides which half of the app the company lives in rather than merely how it looks.
 *
 * <p>The two are deliberately exclusive. A {@link #WAREHOUSE} account keeps no catalogue, orders or
 * tenders of its own - it exists only to work inside the {@link #BUSINESS} companies that have connected
 * it - and a {@link #BUSINESS} account can never be the operator on a connection. That separation is
 * enforced server-side (see {@code PermissionService} and {@code WarehousePartnerService}), not just by
 * hiding navigation.</p>
 */
public enum CompanyType {

    /** An ordinary company: sells, buys, stores and tenders on its own behalf. */
    BUSINESS,

    /** A logistics provider (3PL) whose staff only ever work inside connected client companies. */
    WAREHOUSE,

    /**
     * Skladdo itself. Not a customer at all: the shell that owns the platform operator's login, with no
     * catalogue, orders, tenders, warehouses or clients of its own and nothing to bill. Its people run the
     * service rather than use it, so they see the cross-tenant admin panel and nothing else.
     *
     * <p><strong>Never selectable.</strong> It is created only by {@code PlatformAdminBootstrap} from the
     * deployment's {@code app.platform-admin-emails} property - the same configuration that decides who
     * operates the platform - and {@link #isSelectableAtSignup()} keeps both the public signup and the
     * operator's own "create company" form from producing one.</p>
     */
    PLATFORM;

    /**
     * Whether a signup (public or operator-created) may ask for this type. False only for
     * {@link #PLATFORM}: an account type that exists to run the service is not one anybody chooses.
     * Mirrors {@code PlanType.isSelectable()}.
     */
    public boolean isSelectableAtSignup() {
        return this != PLATFORM;
    }

    /**
     * Whether a company of this type owns catalogue, orders and tenders of its own. False for the two
     * types that exist to work on someone else's data ({@link #WAREHOUSE}) or on the service itself
     * ({@link #PLATFORM}) - see {@code PermissionService.ownsNoBusinessData}.
     */
    public boolean ownsBusinessData() {
        return this == BUSINESS;
    }
}
