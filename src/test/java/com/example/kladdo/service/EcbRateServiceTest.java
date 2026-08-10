package com.example.kladdo.service;

import com.example.kladdo.service.EcbRateService.Rate;
import com.example.kladdo.service.EcbRateService.Snapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the ECB feed parsing and the euro-pivot rate maths - the two pure, network-free pieces of
 * {@link EcbRateService}. A trimmed real-shape ECB daily document drives the parse; the pivot is checked
 * for the base-is-EUR case, a cross pair, same-currency, and an unpublished currency (e.g. RUB).
 */
class EcbRateServiceTest {

    private static final String SAMPLE_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gesmes:Envelope>
              <Cube>
                <Cube time='2026-07-06'>
                  <Cube currency='USD' rate='1.1000'/>
                  <Cube currency='GBP' rate='0.8500'/>
                  <Cube currency='JPY' rate='170.00'/>
                </Cube>
              </Cube>
            </gesmes:Envelope>
            """;

    @Test
    void parsesRatesDateAndAddsEuro() {
        Snapshot snap = EcbRateService.parse(SAMPLE_XML).orElseThrow();
        assertEquals(LocalDate.of(2026, 7, 6), snap.date());
        assertEquals(0, snap.ratesPerEur().get("USD").compareTo(new BigDecimal("1.1000")));
        assertEquals(0, snap.ratesPerEur().get("EUR").compareTo(BigDecimal.ONE)); // EUR added implicitly
    }

    @Test
    void parseEmptyWhenNoRates() {
        assertTrue(EcbRateService.parse("<Envelope></Envelope>").isEmpty());
        assertTrue(EcbRateService.parse("").isEmpty());
    }

    @Test
    void pivotFromEuroBaseIsTheRawRate() {
        Snapshot snap = EcbRateService.parse(SAMPLE_XML).orElseThrow();
        // 1 EUR = 1.10 USD
        Rate r = EcbRateService.pivot(snap, "EUR", "USD").orElseThrow();
        assertEquals(0, r.rate().compareTo(new BigDecimal("1.10")));
    }

    @Test
    void pivotCrossPairThroughEuro() {
        Snapshot snap = EcbRateService.parse(SAMPLE_XML).orElseThrow();
        // 1 GBP = (USD per EUR)/(GBP per EUR) = 1.10 / 0.85 = 1.294118 USD
        Rate r = EcbRateService.pivot(snap, "GBP", "USD").orElseThrow();
        assertEquals(0, r.rate().compareTo(new BigDecimal("1.294118")));
    }

    @Test
    void pivotSameCurrencyIsOne() {
        Snapshot snap = EcbRateService.parse(SAMPLE_XML).orElseThrow();
        assertEquals(0, EcbRateService.pivot(snap, "USD", "USD").orElseThrow().rate().compareTo(BigDecimal.ONE));
    }

    @Test
    void pivotEmptyForUnpublishedCurrency() {
        Snapshot snap = EcbRateService.parse(SAMPLE_XML).orElseThrow();
        // ECB does not publish RUB, so no pair involving it can be derived.
        Optional<Rate> r = EcbRateService.pivot(snap, "RUB", "USD");
        assertTrue(r.isEmpty());
        assertTrue(EcbRateService.pivot(snap, "USD", "RUB").isEmpty());
    }
}
