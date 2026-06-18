package com.example.tenderapp.model;

/**
 * Feature areas that a {@link Role#USER} account can be granted fine-grained access to. Owners and
 * administrators are not constrained by these - they always have full access.
 *
 * <p>Each module maps to one navigable page / API area. Order modules ({@link #PURCHASE_ORDERS},
 * {@link #SALES_ORDERS}) already embed their line-item product details in the response, so a user
 * granted only order access can still read what is inside an order without needing
 * {@link #PRODUCTS} access. The reference modules they need to <em>build</em> an order are opened up
 * automatically - see {@code PermissionService#canReadReference}.</p>
 */
public enum PermissionModule {
    PRODUCTS,
    MANUFACTURERS,
    CATEGORIES,
    CLIENTS,
    PURCHASE_ORDERS,
    SALES_ORDERS,
    TENDERS
}
