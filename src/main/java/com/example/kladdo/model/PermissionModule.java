package com.example.kladdo.model;

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
    /**
     * The partner-category taxonomy used to group clients and manufacturers. Separate from
     * {@link #CATEGORIES} (which classifies products) and managed on its own page.
     */
    PARTNER_CATEGORIES,
    CLIENTS,
    PURCHASE_ORDERS,
    SALES_ORDERS,
    /**
     * Invoicing: generating a PDF invoice for a sales order, marking it paid/unpaid, and voiding it.
     * Independent of {@link #SALES_ORDERS} so finance-only staff can be granted billing access without
     * the full order module (and vice versa). {@code canView} sees the invoice list/detail and downloads
     * PDFs; {@code canCreate} generates invoices; {@code canEdit} changes payment status and voids.
     */
    INVOICES,
    TENDERS,
    /**
     * Stock-taking ("inventarization"): adjusting a product's on-hand quantity up or down with a
     * reason note. {@code canView} sees the adjustment history; {@code canCreate} can post an
     * adjustment. Grantable to both {@link Role#USER} and {@link Role#WAREHOUSE} accounts.
     */
    INVENTORY,
    /**
     * Warehouse management: creating, editing, and deactivating physical warehouse locations.
     * {@code canView} lets a user see the warehouse list and stock levels (required to pick a
     * warehouse on orders). {@code canCreate}/{@code canEdit}/{@code canDelete} govern CRUD.
     * {@link Role#WAREHOUSE} staff receive {@code canView} by default.
     */
    WAREHOUSES
}
