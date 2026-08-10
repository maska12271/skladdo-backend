package com.example.kladdo.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A suggested exchange rate for a currency picker: how many units of {@code currency} one unit of the
 * company {@code base} currency buys ({@code 1 base = rate foreign}). {@code source} tells the UI where it
 * came from so it can show a hint - {@code ECB} (with the {@code asOfDate}), {@code LAST_USED} (the rate
 * last saved for this currency), {@code SAME} (currency equals base, rate 1) or {@code NONE} (no rate
 * known; {@code rate} is 0 for the user to fill in).
 */
public record RateQuoteDto(
        String currency,
        String base,
        BigDecimal rate,
        String source,
        LocalDate asOfDate
) {
}
