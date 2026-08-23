package com.example.skladdo.model;

/**
 * Stock-consumption strategy chosen per product. Determines the order in which {@link ProductBatch}
 * lots are spent when a sales order ships.
 *
 * <ul>
 *   <li>{@code FEFO} – First Expired, First Out: nearest expiry date first; lots without an expiry
 *       date are treated as "never expires" and consumed last (and among themselves, oldest first).</li>
 *   <li>{@code FIFO} – First In, First Out: oldest received lot first.</li>
 *   <li>{@code LIFO} – Last In, First Out: newest received lot first.</li>
 * </ul>
 */
public enum WarehouseMethod {
    FEFO,
    FIFO,
    LIFO
}
