package com.example.skladdo.config;

import com.example.skladdo.model.Currency;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Deployment-level multi-currency configuration ({@code app.currency.*}).
 *
 * <ul>
 *   <li>{@code base-default} — the currency a brand-new company's settings start in.</li>
 *   <li>{@code supported} — the subset of {@link Currency} offered in this deployment. Empty means
 *       "offer every currency the app knows about".</li>
 * </ul>
 *
 * The enabled list is always intersected with (and validated against) {@link Currency}, so an unknown
 * code in configuration is simply ignored rather than breaking startup.
 */
@Component
@ConfigurationProperties(prefix = "app.currency")
public class CurrencyProperties {

    private String baseDefault = "EUR";
    private List<String> supported = new ArrayList<>();

    /** The known currencies enabled for this deployment (config subset, or all when unset). */
    public List<Currency> enabledCurrencies() {
        if (supported == null || supported.isEmpty()) {
            return List.of(Currency.values());
        }
        return supported.stream()
                .map(Currency::of)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .distinct()
                .toList();
    }

    public String getBaseDefault() {
        return baseDefault;
    }

    public void setBaseDefault(String baseDefault) {
        this.baseDefault = baseDefault;
    }

    public List<String> getSupported() {
        return supported;
    }

    public void setSupported(List<String> supported) {
        this.supported = supported;
    }
}
