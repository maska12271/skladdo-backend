package com.example.kladdo.controller;

import com.example.kladdo.config.CurrencyProperties;
import com.example.kladdo.dto.CurrencyDto;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The catalogue of currencies this deployment offers, used by the frontend to populate currency pickers
 * and render symbols. Read-only reference data available to any authenticated user.
 */
@RestController
@RequestMapping("/api/currencies")
@Tag(name = "Currencies")
public class CurrencyController {

    private final CurrencyProperties currencyProperties;

    public CurrencyController(CurrencyProperties currencyProperties) {
        this.currencyProperties = currencyProperties;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<CurrencyDto> getAll() {
        return currencyProperties.enabledCurrencies().stream()
                .map(CurrencyDto::from)
                .toList();
    }
}
