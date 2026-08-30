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
        String penaltyPeriod,
        // Day the week starts on in calendars, ISO-8601 (1 = Monday ... 7 = Sunday), or null to follow the
        // viewer's locale. Here rather than only in the admin-only settings because every user sees a date
        // picker, not just administrators.
        Integer firstDayOfWeek,
        // How dates and times are written across the app, or null to follow the viewer's language. Here
        // for the same reason as firstDayOfWeek: every user sees dates, not just administrators.
        String dateFormat,
        String timeFormat,
        // Unit a new product/line starts on (e.g. "pcs"). Here rather than only in the admin-only settings
        // because the unit pickers it seeds are used by everyone, not just administrators.
        String defaultProductUnit
) {
}
