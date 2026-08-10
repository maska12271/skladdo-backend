package com.example.kladdo.dto;

import java.time.Instant;

/**
 * One line of a warehouse's stock ledger for a single product. Computed on read by unioning the existing
 * sources - there is no stock-movement table - so this is a view, never persisted.
 *
 * @param balanceAfter on-hand quantity in this warehouse immediately after the movement. Derived by walking
 *                     backwards from the current {@code WarehouseStock}, so the newest line always matches
 *                     today's stock exactly; older lines can drift if an order's lines were edited after it
 *                     shipped (the replay uses the order's current quantities).
 */
public record StockMovementDto(
        Instant timestamp,
        Kind kind,
        int quantityChange,
        int balanceAfter,
        /** What the movement points at, e.g. an order number or the other warehouse in a transfer. */
        String reference,
        /** Free text carried by the source row (an adjustment's or transfer's note). */
        String note,
        /** Which record this came from, so the client can link to it. */
        String sourceType,
        Long sourceId,
        Long actorUserId,
        String actorName
) {
    /** The kind of movement, which also tells the client whether stock came in or went out. */
    public enum Kind {
        /** A purchase order reached a stock-affecting status: goods received. */
        PURCHASE_IN,
        /** A purchase order left a stock-affecting status: the receipt was undone. */
        PURCHASE_OUT,
        /** A sales order reached a stock-affecting status: goods issued to the customer. */
        SALES_OUT,
        /** A sales order left a stock-affecting status: the issue was undone. */
        SALES_IN,
        TRANSFER_IN,
        TRANSFER_OUT,
        /** A manual stock-take correction. */
        ADJUSTMENT
    }
}
