package com.example.skladdo.model;

/**
 * Lifecycle of a {@link WarehouseConnection}. There is no pending state: redeeming a
 * {@link ConnectionCode} is itself the client's consent, so a connection starts live.
 *
 * <ul>
 *     <li>{@link #ACTIVE} - the warehouse company's staff may switch into the client company.</li>
 *     <li>{@link #REVOKED} - ended by either side. Kept rather than deleted so the history of who had
 *     access to a company's data survives.</li>
 * </ul>
 */
public enum ConnectionStatus {
    ACTIVE,
    REVOKED
}
