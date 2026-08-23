package com.example.skladdo.dto;

import java.math.BigDecimal;

/**
 * The subset of company settings any authenticated user may read so the UI can render prices
 * consistently (currency, whether to show tax-inclusive prices, and the default tax percentage used
 * for products without their own rate). Unlike the full {@link CompanySettingsDto}, this carries no
 * business configuration and is therefore not restricted to administrators.
 */
public record DisplaySettingsDto(
        String currency,
        boolean pricesIncludeTax,
        BigDecimal defaultTaxPercent,
        Long defaultWarehouseId,
        // Company invoicing defaults, so the "Create invoice" dialog can prefill payment terms, penalty
        // and the prepayment without exposing the (admin-only) full settings.
        BigDecimal defaultPrepaymentPercent,
        Integer invoicePaymentTermDays,
        BigDecimal latePaymentPenaltyPercent,
        String penaltyPeriod
) {
}
