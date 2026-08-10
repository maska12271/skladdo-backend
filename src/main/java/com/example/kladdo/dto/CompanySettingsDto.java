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
        // IANA timezone (e.g. "Europe/Tallinn"). Optional: a null/unknown value reads back as UTC.
        @Size(max = 64) String timezone,

        @NotBlank String invoiceNumberPrefix,
        // Configurable prefix for suggested tender numbers. Optional: blank/null reads back as "TND-".
        @Size(max = 20) String tenderNumberPrefix,
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
        // Language a newly invited user starts in. Optional: an unknown/blank value reads back as "en".
        @Size(max = 5) String defaultUserLanguage,

        // Warehouse pre-selected on new orders (optional; null = no default).
        Long defaultWarehouseId,

        // Email / SMTP (all optional). The password is write-only: it is never returned (see from()), and
        // is only re-encrypted on write when a new non-blank value is supplied (blank = keep existing).
        // hasPassword and smtpConfigured are read-only outputs the UI uses; they are ignored on write.
        // They are Boolean (not primitive) so the client can omit them on PUT without Jackson 3 failing
        // to map an absent primitive - the frontend's save payload does not send them back.
        String smtpHost,
        Integer smtpPort,
        String smtpUsername,
        String smtpFromAddress,
        String smtpFromName,
        Boolean smtpUseTls,
        String smtpPassword,
        Boolean hasPassword,
        Boolean smtpConfigured
) {
    public static CompanySettingsDto from(CompanySettings s) {
        return new CompanySettingsDto(
                s.getCurrency(),
                s.isPricesIncludeTax(),
                s.getTimezone(),
                s.getInvoiceNumberPrefix(),
                s.getTenderNumberPrefix(),
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
                s.getDefaultUserLanguage(),
                s.getDefaultWarehouseId(),
                s.getSmtpHost(),
                s.getSmtpPort(),
                s.getSmtpUsername(),
                s.getSmtpFromAddress(),
                s.getSmtpFromName(),
                s.getSmtpUseTls(),
                null, // never expose the stored password (not even the ciphertext)
                s.getSmtpPasswordEncrypted() != null && !s.getSmtpPasswordEncrypted().isBlank(),
                smtpConfigured(s)
        );
    }

    /** SMTP is usable once host, port, username and from-address are all present. */
    private static boolean smtpConfigured(CompanySettings s) {
        return notBlank(s.getSmtpHost())
                && s.getSmtpPort() != null
                && notBlank(s.getSmtpUsername())
                && notBlank(s.getSmtpFromAddress());
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** Copies the editable fields onto an entity (used for both create and update). */
    public void applyTo(CompanySettings s) {
        s.setCurrency(currency);
        s.setPricesIncludeTax(pricesIncludeTax);
        s.setTimezone(timezone);
        s.setInvoiceNumberPrefix(invoiceNumberPrefix);
        s.setTenderNumberPrefix(tenderNumberPrefix);
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
        s.setDefaultUserLanguage(defaultUserLanguage);
        s.setDefaultWarehouseId(defaultWarehouseId);
        // Non-secret SMTP fields. The password is handled separately in CompanySettingsService (it needs
        // encryption and "blank = keep existing" semantics), so it is deliberately not copied here.
        s.setSmtpHost(smtpHost);
        s.setSmtpPort(smtpPort);
        s.setSmtpUsername(smtpUsername);
        s.setSmtpFromAddress(smtpFromAddress);
        s.setSmtpFromName(smtpFromName);
        s.setSmtpUseTls(smtpUseTls);
    }
}
