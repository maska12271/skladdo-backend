package com.example.skladdo.service;

import com.example.skladdo.dto.RateQuoteDto;
import com.example.skladdo.model.ExchangeRate;
import com.example.skladdo.repository.ExchangeRateRepository;
import com.example.skladdo.repository.PurchaseOrderRepository;
import com.example.skladdo.repository.SalesOrderRepository;
import com.example.skladdo.repository.TenderRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Resolves the exchange rate to suggest for a transaction currency and remembers the rates a company has
 * actually used. Rates are oriented as {@code 1 base = rate foreign} (company base currency on the left,
 * matching the form). A quote is resolved ECB first, then the last rate the company used for that currency,
 * then 0 (so the user fills it in). There is no manual rate table - the {@link ExchangeRate} rows are a
 * write-through cache fed by saved transactions.
 */
@Service
public class ExchangeRateService {

    private final ExchangeRateRepository repository;
    private final EcbRateService ecbRateService;
    private final CompanySettingsService settingsService;
    private final SalesOrderRepository salesOrderRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final TenderRepository tenderRepository;

    public ExchangeRateService(ExchangeRateRepository repository,
                               EcbRateService ecbRateService,
                               CompanySettingsService settingsService,
                               SalesOrderRepository salesOrderRepository,
                               PurchaseOrderRepository purchaseOrderRepository,
                               TenderRepository tenderRepository) {
        this.repository = repository;
        this.ecbRateService = ecbRateService;
        this.settingsService = settingsService;
        this.salesOrderRepository = salesOrderRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.tenderRepository = tenderRepository;
    }

    /**
     * The company base/reporting currency (falls back to EUR when unset). Not read-only: on a company's
     * first call, {@code getOrCreate()} inserts the default settings row, which Postgres rejects inside a
     * read-only transaction.
     */
    @Transactional
    public String baseCurrency() {
        String base = settingsService.getOrCreate().getCurrency();
        return base != null && !base.isBlank() ? base : "EUR";
    }

    /**
     * The rate to prefill for a chosen currency: ECB when it publishes the pair, else the rate the company
     * last used for that currency, else 0. Same-as-base yields 1.
     *
     * <p>Not read-only, for the same reason as {@link #baseCurrency()}.</p>
     */
    @Transactional
    public RateQuoteDto quote(String currency) {
        String base = baseCurrency();
        String foreign = currency == null || currency.isBlank() ? base : currency.trim().toUpperCase();

        if (foreign.equals(base)) {
            return new RateQuoteDto(foreign, base, BigDecimal.ONE, "SAME", LocalDate.now());
        }
        return ecbRateService.quote(base, foreign)
                .map(r -> new RateQuoteDto(foreign, base, r.rate(), "ECB", r.asOfDate()))
                .orElseGet(() -> repository.findByCurrencyCode(foreign)
                        .map(c -> new RateQuoteDto(foreign, base, c.getRate(), "LAST_USED", c.getAsOfDate()))
                        .orElseGet(() -> new RateQuoteDto(foreign, base, BigDecimal.ZERO, "NONE", null)));
    }

    /**
     * Remembers the rate a saved transaction used for a currency, so it can be offered next time the ECB
     * has no rate for it. No-op for the base currency, a blank currency, or a non-positive rate.
     */
    @Transactional
    public void recordUsedRate(String currency, BigDecimal rate) {
        if (currency == null || currency.isBlank() || rate == null || rate.signum() <= 0) {
            return;
        }
        String code = currency.trim().toUpperCase();
        if (code.equals(baseCurrency())) {
            return;
        }
        ExchangeRate cached = repository.findByCurrencyCode(code).orElseGet(ExchangeRate::new);
        cached.setCurrencyCode(code);
        cached.setRate(rate);
        cached.setAsOfDate(LocalDate.now());
        cached.setSource("MANUAL");
        repository.save(cached);
    }

    /** The currency used most often for the given scope, or the base currency when there is no history. */
    @Transactional(readOnly = true)
    public String mostUsedCurrency(String scope) {
        PageRequest topOne = PageRequest.of(0, 1);
        List<String> currencies = switch (scope == null ? "" : scope.trim().toUpperCase()) {
            case "SALES_ORDER" -> salesOrderRepository.findMostUsedCurrencies(topOne);
            case "PURCHASE_ORDER" -> purchaseOrderRepository.findMostUsedCurrencies(topOne);
            case "TENDER" -> tenderRepository.findMostUsedCurrencies(topOne);
            default -> List.of();
        };
        return currencies.isEmpty() ? baseCurrency() : currencies.get(0);
    }
}
