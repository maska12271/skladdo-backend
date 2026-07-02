package com.example.kladdo.dto;

import com.example.kladdo.model.CompanySettings;
import com.example.kladdo.model.InvoiceTemplate;
import com.example.kladdo.model.PenaltyPeriod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Read/write view of a company's settings. The same shape is returned by {@code GET /api/settings}
 * and accepted by {@code PUT /api/settings}.
 */
public record CompanySettingsDto(
        @NotBlank @Size(min = 3, max = 3) String currency,
        boolean pricesIncludeTax,

        @NotBlank String invoiceNumberPrefix,
        @NotNull @Min(0) Integer invoicePaymentTermDays,
        @NotNull @DecimalMin("0.0") BigDecimal latePaymentPenaltyPercent,
        @NotNull PenaltyPeriod penaltyPeriod,
        @NotNull @DecimalMin("0.0") BigDecimal defaultPrepaymentPercent,

        // Seller details printed on the invoice header (all optional).
        String companyAddress,
        String companyEmail,
        String companyPhone,
        String vatNumber,
        String bankName,
        String bankIban,
        String logoUrl,

        // Invoice PDF appearance: which layout to render and what to show on it.
        @NotNull InvoiceTemplate invoiceTemplate,
        String invoiceAccentColor,
        boolean invoiceShowLogo,
        boolean invoiceShowLineSku,
        boolean invoiceShowPaymentTerms,
        boolean invoiceShowBankDetails,
        boolean invoiceShowNotes,
        String invoiceFooterText,

        @NotBlank String defaultProductUnit,
        @NotNull @Min(0) Integer defaultMinimumStock,

        // Warehouse pre-selected on new orders (optional; null = no default).
        Long defaultWarehouseId
) {
    public static CompanySettingsDto from(CompanySettings s) {
        return new CompanySettingsDto(
                s.getCurrency(),
                s.isPricesIncludeTax(),
                s.getInvoiceNumberPrefix(),
                s.getInvoicePaymentTermDays(),
                s.getLatePaymentPenaltyPercent(),
                s.getPenaltyPeriod(),
                s.getDefaultPrepaymentPercent(),
                s.getCompanyAddress(),
                s.getCompanyEmail(),
                s.getCompanyPhone(),
                s.getVatNumber(),
                s.getBankName(),
                s.getBankIban(),
                s.getLogoUrl(),
                s.getInvoiceTemplate(),
                s.getInvoiceAccentColor(),
                s.getInvoiceShowLogo(),
                s.getInvoiceShowLineSku(),
                s.getInvoiceShowPaymentTerms(),
                s.getInvoiceShowBankDetails(),
                s.getInvoiceShowNotes(),
                s.getInvoiceFooterText(),
                s.getDefaultProductUnit(),
                s.getDefaultMinimumStock(),
                s.getDefaultWarehouseId()
        );
    }

    /** Copies the editable fields onto an entity (used for both create and update). */
    public void applyTo(CompanySettings s) {
        s.setCurrency(currency);
        s.setPricesIncludeTax(pricesIncludeTax);
        s.setInvoiceNumberPrefix(invoiceNumberPrefix);
        s.setInvoicePaymentTermDays(invoicePaymentTermDays);
        s.setLatePaymentPenaltyPercent(latePaymentPenaltyPercent);
        s.setPenaltyPeriod(penaltyPeriod);
        s.setDefaultPrepaymentPercent(defaultPrepaymentPercent);
        s.setCompanyAddress(companyAddress);
        s.setCompanyEmail(companyEmail);
        s.setCompanyPhone(companyPhone);
        s.setVatNumber(vatNumber);
        s.setBankName(bankName);
        s.setBankIban(bankIban);
        s.setLogoUrl(logoUrl);
        s.setInvoiceTemplate(invoiceTemplate);
        s.setInvoiceAccentColor(invoiceAccentColor);
        s.setInvoiceShowLogo(invoiceShowLogo);
        s.setInvoiceShowLineSku(invoiceShowLineSku);
        s.setInvoiceShowPaymentTerms(invoiceShowPaymentTerms);
        s.setInvoiceShowBankDetails(invoiceShowBankDetails);
        s.setInvoiceShowNotes(invoiceShowNotes);
        s.setInvoiceFooterText(invoiceFooterText);
        s.setDefaultProductUnit(defaultProductUnit);
        s.setDefaultMinimumStock(defaultMinimumStock);
        s.setDefaultWarehouseId(defaultWarehouseId);
    }
}
