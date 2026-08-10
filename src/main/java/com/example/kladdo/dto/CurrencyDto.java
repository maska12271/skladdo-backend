package com.example.kladdo.dto;

import com.example.kladdo.model.Currency;

/**
 * A currency offered by the deployment, for the {@code GET /api/currencies} catalogue the frontend uses to
 * populate currency pickers and render symbols.
 */
public record CurrencyDto(
        String code,
        String symbol,
        String name,
        int decimals
) {
    public static CurrencyDto from(Currency currency) {
        return new CurrencyDto(currency.code(), currency.symbol(), currency.displayName(), currency.decimals());
    }
}
