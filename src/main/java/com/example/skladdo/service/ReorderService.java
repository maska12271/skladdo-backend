package com.example.skladdo.service;

import com.example.skladdo.dto.ReorderSuggestionDto;
import com.example.skladdo.model.Product;
import com.example.skladdo.repository.ProductRepository;
import com.example.skladdo.repository.PurchaseOrderItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out what needs reordering: the products at or below their minimum stock, with a suggested order
 * quantity and the price last paid, ready to be turned into a purchase order.
 *
 * <p>Uses the same rule as the low-stock notification ({@code stockQuantity < minimumStock}), so the two
 * never disagree about what to reorder. The dashboard's low-stock figure is deliberately <em>wider</em> -
 * it also counts products that are simply empty, minimum or no minimum (see
 * {@code DashboardService.needsRestocking}). Reordering needs a minimum to have a target quantity, so a
 * product without one is not something this can suggest ordering; a card that says "needs attention" has
 * no such constraint.</p>
 */
@Service
public class ReorderService {

    private final ProductRepository productRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;

    public ReorderService(ProductRepository productRepository,
                          PurchaseOrderItemRepository purchaseOrderItemRepository) {
        this.productRepository = productRepository;
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
    }

    /**
     * How many units to suggest ordering: the configured batch size when the product has one, otherwise
     * just enough to climb back to the minimum. Never less than one - a product only appears here because
     * it is already short.
     */
    static int suggestedQuantity(Integer reorderQuantity, int stockQuantity, int minimumStock) {
        if (reorderQuantity != null && reorderQuantity > 0) {
            return reorderQuantity;
        }
        return Math.max(1, minimumStock - stockQuantity);
    }

    /** Products below their minimum, worst shortfall first. */
    @Transactional(readOnly = true)
    public List<ReorderSuggestionDto> suggestions() {
        List<Product> low = productRepository.findLowStock();
        if (low.isEmpty()) {
            return List.of();
        }

        Map<Long, BigDecimal> lastPrices = lastPurchasePrices(low.stream().map(Product::getId).toList());

        return low.stream()
                .map(p -> {
                    int stock = nz(p.getStockQuantity());
                    int minimum = nz(p.getMinimumStock());
                    return new ReorderSuggestionDto(
                            p.getId(),
                            p.getName(),
                            p.getSku(),
                            p.getUnit(),
                            p.getManufacturer() != null ? p.getManufacturer().getId() : null,
                            p.getManufacturer() != null ? p.getManufacturer().getName() : null,
                            stock,
                            minimum,
                            suggestedQuantity(p.getReorderQuantity(), stock, minimum),
                            lastPrices.get(p.getId()));
                })
                // Worst shortfall first, so whatever is most urgent is at the top of the list.
                .sorted(Comparator.comparingInt(s -> s.stockQuantity() - s.minimumStock()))
                .toList();
    }

    /**
     * Most recent purchase price per product, converted to the company's base currency (the reorder view
     * and the draft order it creates are both always in base currency - the order that set this price may
     * not have been), from one query over all the low-stock products.
     */
    private Map<Long, BigDecimal> lastPurchasePrices(List<Long> productIds) {
        Map<Long, BigDecimal> prices = new HashMap<>();
        for (Object[] row : purchaseOrderItemRepository.findRecentPricesForProducts(productIds)) {
            Long productId = ((Number) row[0]).longValue();
            // Rows arrive newest first, so the first one seen for a product is the latest price.
            prices.putIfAbsent(productId, MoneyConverter.toBase((BigDecimal) row[1], (BigDecimal) row[2]));
        }
        return prices;
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
