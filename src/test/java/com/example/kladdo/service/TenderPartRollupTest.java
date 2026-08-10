package com.example.kladdo.service;

import com.example.kladdo.service.TenderPartService.PartSummary;
import com.example.kladdo.service.TenderPartService.Rollup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the pure part-rollup counting - the won/lost/pending/participating tallies and the
 * value-participating sum a tender shows across its parts. Kept dependency-free by exercising the static
 * helper directly.
 */
class TenderPartRollupTest {

    // args: estimatedValue, weParticipate, weWon, anyWinner
    private static PartSummary part(Double value, boolean we, boolean weWon, boolean anyWinner) {
        return new PartSummary(value, we, weWon, anyWinner);
    }

    @Test
    void countsParticipationWinsLossesAndPending() {
        Rollup r = TenderPartService.computeRollup(List.of(
                part(100.0, true, true, true),    // we won
                part(200.0, true, false, true),   // someone else won -> lost (we took part)
                part(300.0, true, false, false),  // undecided, we take part -> pending
                part(400.0, false, false, true),  // we don't take part -> ignored in our tallies
                part(500.0, false, false, false)  // not participating, undecided -> ignored
        ));
        assertEquals(5, r.partCount());
        assertEquals(3, r.participating());       // parts 1, 2, 3
        assertEquals(1, r.won());
        assertEquals(1, r.lost());
        assertEquals(1, r.pending());
        assertEquals(0, Double.compare(600.0, r.participatingValue())); // 100 + 200 + 300
    }

    @Test
    void notParticipatingButWonIsNotPossibleToMiscount() {
        // A part where someone else won and we did not take part is neither lost nor pending for us.
        Rollup r = TenderPartService.computeRollup(List.of(
                part(100.0, false, false, true)
        ));
        assertEquals(1, r.partCount());
        assertEquals(0, r.participating());
        assertEquals(0, r.won());
        assertEquals(0, r.lost());
        assertEquals(0, r.pending());
        assertEquals(0, Double.compare(0.0, r.participatingValue()));
    }

    @Test
    void nullEstimatedValueContributesZero() {
        Rollup r = TenderPartService.computeRollup(List.of(
                part(null, true, false, false),
                part(250.0, true, false, false)
        ));
        assertEquals(2, r.participating());
        assertEquals(2, r.pending());
        assertEquals(0, Double.compare(250.0, r.participatingValue()));
    }

    @Test
    void emptyTenderRollsUpToZeros() {
        Rollup r = TenderPartService.computeRollup(List.of());
        assertEquals(0, r.partCount());
        assertEquals(0, r.participating());
        assertEquals(0, r.won());
        assertEquals(0, r.lost());
        assertEquals(0, r.pending());
    }
}
