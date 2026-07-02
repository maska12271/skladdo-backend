package com.example.kladdo.model;

/**
 * Account roles within a company.
 * <ul>
 *     <li>{@link #OWNER} - top-level account of a company, cannot be removed through the API.</li>
 *     <li>{@link #ADMINISTRATOR} - can manage (add / archive / delete) regular users and administrators.</li>
 *     <li>{@link #USER} - regular user with access to the company data only.</li>
 *     <li>{@link #WAREHOUSE} - warehouse staff; like {@link #USER} they are governed by fine-grained
 *     module permissions, but they additionally carry a {@code canSeePrices} flag and are oriented
 *     around order fulfilment and stock keeping (inventory adjustments).</li>
 * </ul>
 *
 * <p>{@link #OWNER} and {@link #ADMINISTRATOR} are "manager" roles that bypass all permission checks.
 * {@link #USER} and {@link #WAREHOUSE} are "restricted" roles whose access is driven by their
 * {@code UserPermission} rows.</p>
 */
public enum Role {
    OWNER,
    ADMINISTRATOR,
    USER,
    WAREHOUSE
}
