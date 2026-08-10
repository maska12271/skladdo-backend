package com.example.kladdo.dto;

import java.math.BigDecimal;

/**
 * One product that has fallen to or below its minimum stock, with everything the reorder view needs to
 * turn it into a purchase-order line.
 *
 * @param suggestedQuantity how many to order - the product's configured {@code reorderQuantity} when set,
 *                          otherwise just enough to climb back to {@code minimumStock}.
 * @param lastPurchasePrice what was last paid per unit, or {@code null} if this product has never been
 *                          purchased (the buyer then fills the price in themselves).
 */
public record ReorderSuggestionDto(
        Long productId,
        String productName,
        String sku,
        String unit,
        Long manufacturerId,
        String manufacturerName,
        int stockQuantity,
        int minimumStock,
        int suggestedQuantity,
        BigDecimal lastPurchasePrice
) {
}
