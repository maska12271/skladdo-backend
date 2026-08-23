package com.example.skladdo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Converts a transaction's amount into the company's base currency using the rate snapshotted on that
 * transaction.
 *
 * <p>Rates mean <strong>{@code 1 base = rate foreign}</strong> (see {@code ExchangeRateService}), so the
 * base-currency value is {@code amount / rate}. A missing or non-positive rate means the amount is already
 * in the base currency and is returned as-is.</p>
 *
 * <p><strong>When to use it.</strong> Any figure that adds up amounts from more than one transaction has to
 * go through here, because those transactions can be in different currencies - summing them raw reports
 * "100 USD + 100 EUR = 200", which is what every analytics roll-up used to do. A view of a
 * <em>single</em> order deliberately does not convert: it shows that order in its own currency and hands the
 * client the rate to render the base-currency equivalent alongside.</p>
 */
public final class MoneyConverter {

    private MoneyConverter() {
    }

    /** Base-currency value of an amount carrying {@code rate}. Null amounts count as zero. */
    public static BigDecimal toBase(BigDecimal amount, BigDecimal rate) {
        BigDecimal value = amount != null ? amount : BigDecimal.ZERO;
        if (rate == null || rate.signum() <= 0) {
            return value.setScale(2, RoundingMode.HALF_UP);
        }
        return value.divide(rate, 2, RoundingMode.HALF_UP);
    }

    /** Same, for the {@code Double} amounts tenders still use. */
    public static BigDecimal toBase(Double amount, BigDecimal rate) {
        return toBase(amount == null ? null : BigDecimal.valueOf(amount), rate);
    }
}
