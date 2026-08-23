package com.example.skladdo.controller;

import com.example.skladdo.dto.RateQuoteDto;
import com.example.skladdo.service.ExchangeRateService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exchange-rate helpers for the order/tender forms: a suggested rate for a chosen currency (ECB, then the
 * company's last-used rate, then 0) and the currency the company uses most for a given document type.
 * Read-only for any authenticated user; there is no manual rate management.
 */
@RestController
@RequestMapping("/api/exchange-rates")
@Tag(name = "Exchange Rates")
public class ExchangeRateController {

    private final ExchangeRateService exchangeRateService;

    public ExchangeRateController(ExchangeRateService exchangeRateService) {
        this.exchangeRateService = exchangeRateService;
    }

    /** Suggested rate ({@code 1 base = rate currency}) to prefill when a currency is selected. */
    @GetMapping("/quote")
    @PreAuthorize("isAuthenticated()")
    public RateQuoteDto quote(@RequestParam String currency) {
        return exchangeRateService.quote(currency);
    }

    /** The most-used currency for a document type (scope = SALES_ORDER | PURCHASE_ORDER | TENDER). */
    @GetMapping("/most-used")
    @PreAuthorize("isAuthenticated()")
    public RateQuoteDto mostUsed(@RequestParam(defaultValue = "") String scope) {
        return exchangeRateService.quote(exchangeRateService.mostUsedCurrency(scope));
    }
}
