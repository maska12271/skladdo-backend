package com.example.skladdo.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * The set of currencies the application understands, with the metadata needed to render and round money:
 * an ISO&nbsp;4217 code (the enum name), a display symbol, the number of minor-unit decimal places and a
 * human name. Monetary amounts are stored as a plain {@code String(3)} code on the domain entities (see
 * {@link CompanySettings#getCurrency()}, {@link Invoice#getCurrency()}); this enum is the reference/lookup
 * used for validation, the {@code GET /api/currencies} catalogue and per-currency rounding.
 *
 * <p>Which of these are actually offered in a given deployment is narrowed by
 * {@code app.currency.supported} (see {@code CurrencyProperties}); this enum is the full known universe.</p>
 */
public enum Currency {

    EUR("€", 2, "Euro"),
    USD("$", 2, "US Dollar"),
    GBP("£", 2, "British Pound"),
    CHF("CHF", 2, "Swiss Franc"),
    SEK("kr", 2, "Swedish Krona"),
    NOK("kr", 2, "Norwegian Krone"),
    DKK("kr", 2, "Danish Krone"),
    PLN("zł", 2, "Polish Złoty"),
    CZK("Kč", 2, "Czech Koruna"),
    CAD("$", 2, "Canadian Dollar"),
    AUD("$", 2, "Australian Dollar"),
    JPY("¥", 0, "Japanese Yen");

    private final String symbol;
    private final int decimals;
    private final String displayName;

    Currency(String symbol, int decimals, String displayName) {
        this.symbol = symbol;
        this.decimals = decimals;
        this.displayName = displayName;
    }

    /** The ISO 4217 code (identical to the enum name), e.g. {@code "EUR"}. */
    public String code() {
        return name();
    }

    public String symbol() {
        return symbol;
    }

    /** Minor-unit decimal places used when rounding amounts in this currency (0 for e.g. JPY). */
    public int decimals() {
        return decimals;
    }

    public String displayName() {
        return displayName;
    }

    /** Looks up a currency by ISO code (case-insensitive), or empty if it is not a known currency. */
    public static Optional<Currency> of(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase();
        return Arrays.stream(values()).filter(c -> c.name().equals(normalized)).findFirst();
    }

    public static boolean isSupported(String code) {
        return of(code).isPresent();
    }

    /** Decimal places for a code, falling back to 2 for an unknown/blank code. */
    public static int decimalsFor(String code) {
        return of(code).map(Currency::decimals).orElse(2);
    }
}
