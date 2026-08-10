package com.example.kladdo.service;

import com.example.kladdo.dto.StockMovementDto;
import com.example.kladdo.model.OrderStatus;
import com.example.kladdo.service.StockLedgerService.RawMovement;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the stock ledger's arithmetic - the running balance walked backwards from today's stock,
 * and the rule that decides when an order actually moves stock.
 *
 * <p>Kept to the pure helpers (no Spring, no database) in the style of the other tests here. The property
 * that matters most is the one asserted first: the newest line must always agree with current stock, since
 * the ledger exists to answer "why is my stock this number?".</p>
 */
class StockLedgerBalanceTest {

    private static RawMovement move(int secondsFromEpoch, int delta, long sourceId) {
        return new RawMovement(Instant.ofEpochSecond(secondsFromEpoch), StockMovementDto.Kind.ADJUSTMENT,
                delta, null, null, "INVENTORY_ADJUSTMENT", sourceId, null);
    }

    @Test
    void newestLineMatchesCurrentStockAndBalancesChainBackwards() {
        // Chronologically: +10, -4, +2  => current stock 8.
        List<RawMovement> raw = List.of(move(300, 2, 3), move(100, 10, 1), move(200, -4, 2));

        List<StockMovementDto> ledger = StockLedgerService.withBalances(raw, 8, Map.of());

        assertEquals(3, ledger.size());
        // Returned newest first.
        assertEquals(2, ledger.get(0).quantityChange());
        assertEquals(8, ledger.get(0).balanceAfter(), "newest balance is today's stock");
        assertEquals(-4, ledger.get(1).quantityChange());
        assertEquals(6, ledger.get(1).balanceAfter());
        assertEquals(10, ledger.get(2).quantityChange());
        assertEquals(10, ledger.get(2).balanceAfter(), "first ever movement took stock from 0 to 10");
    }

    @Test
    void balancesAreSelfConsistent() {
        List<RawMovement> raw = List.of(move(100, 5, 1), move(200, 7, 2), move(300, -3, 3), move(400, -2, 4));

        List<StockMovementDto> ledger = StockLedgerService.withBalances(raw, 7, Map.of());

        // Every line's balance must equal the previous line's balance plus this movement.
        for (int i = ledger.size() - 1; i > 0; i--) {
            assertEquals(ledger.get(i).balanceAfter() + ledger.get(i - 1).quantityChange(),
                    ledger.get(i - 1).balanceAfter(),
                    "balance chain broken between lines " + i + " and " + (i - 1));
        }
    }

    @Test
    void handlesAnEmptyLedger() {
        assertTrue(StockLedgerService.withBalances(List.of(), 0, Map.of()).isEmpty());
    }

    @Test
    void resolvesActorNames() {
        RawMovement withActor = new RawMovement(Instant.ofEpochSecond(100), StockMovementDto.Kind.ADJUSTMENT,
                3, null, "stock take", "INVENTORY_ADJUSTMENT", 1L, 42L);

        List<StockMovementDto> ledger = StockLedgerService.withBalances(List.of(withActor), 3, Map.of(42L, "Anna"));

        assertEquals("Anna", ledger.get(0).actorName());
        assertEquals(42L, ledger.get(0).actorUserId());
    }

    /** Only SHIPPED/CLOSED hold stock - the same rule the sales and purchase services apply. */
    @Test
    void onlyShippedAndClosedHoldStock() {
        assertTrue(StockLedgerService.isStockAffecting(OrderStatus.SHIPPED));
        assertTrue(StockLedgerService.isStockAffecting(OrderStatus.CLOSED));
        assertFalse(StockLedgerService.isStockAffecting(OrderStatus.NEW));
        assertFalse(StockLedgerService.isStockAffecting(OrderStatus.IN_PROGRESS));
        assertFalse(StockLedgerService.isStockAffecting(OrderStatus.CONFIRMED));
        assertFalse(StockLedgerService.isStockAffecting(OrderStatus.CANCELLED));
        assertFalse(StockLedgerService.isStockAffecting(null), "an order's first status change comes from null");
    }
}
