package com.example.kladdo.model;

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
    WAREHOUSE
}
