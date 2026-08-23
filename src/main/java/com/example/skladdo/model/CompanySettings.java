package com.example.skladdo.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Company-wide preferences and business defaults, edited by owners/administrators on the settings page.
 * Exactly one row per company (created lazily on first access). {@code @TenantId}-scoped so each company
 * only ever sees its own settings.
 *
 * <p>Holds the configurable defaults used elsewhere - invoice payment terms, late-payment penalty,
 * prepayment, the currency and tax-display preference, and the defaults stamped onto new products.</p>
 */
@Entity
@Getter
@Setter
@EntityListeners(AuditingEntityListener.class)
public class CompanySettings {

    /** Timezone used when a company has not chosen one (or chose one this JVM does not recognise). */
    private static final String DEFAULT_TIMEZONE = "UTC";

    /** Interface languages the app ships; keep in sync with the frontend's i18n locales. */
    public static final java.util.List<String> SUPPORTED_LANGUAGES = java.util.List.of("en", "et", "ru");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    // --- Display ---------------------------------------------------------------------------------

    /** ISO 4217 currency code used to render and store monetary amounts (e.g. "EUR"). */
    @Column(nullable = false, length = 3)
    private String currency = "EUR";

    /** Whether prices are shown tax-inclusive across the app. Display preference only; stored prices stay net. */
    @Column(nullable = false)
    private boolean pricesIncludeTax = false;

    /**
     * IANA timezone the company operates in (e.g. {@code "Europe/Tallinn"}), used to interpret dates and to
     * time scheduled work at a sensible local hour. Nullable with an effective-value getter so the column
     * adds cleanly to an existing settings row under {@code ddl-auto=update} - the same migration-friendly
     * pattern as the invoice-appearance fields below.
     */
    private String timezone;

    /**
     * Effective IANA timezone, falling back to UTC when unset or when the stored value is not a zone this
     * JVM knows. Validating on read (rather than trusting the column) keeps a bad value - from an old row
     * or a direct API call - from breaking scheduled work later.
     */
    public String getTimezone() {
        if (timezone == null || timezone.isBlank()) {
            return DEFAULT_TIMEZONE;
        }
        return ZoneId.getAvailableZoneIds().contains(timezone) ? timezone : DEFAULT_TIMEZONE;
    }

    // --- Invoicing -------------------------------------------------------------------------------

    /** Prefix for generated invoice numbers (e.g. "INV-"). */
    @Column(nullable = false)
    private String invoiceNumberPrefix = "INV-";

    /**
     * Highest invoice sequence number issued so far; the next invoice gets {@code lastInvoiceNumber + 1},
     * zero-padded behind {@link #invoiceNumberPrefix}. Kept nullable at the DB level (treated as {@code 0}
     * when read) so the column can be added to an existing settings row under {@code ddl-auto=update} -
     * same approach as {@link Product#getWarehouseMethod()}.
     */
    private Integer lastInvoiceNumber;

    /**
     * Highest sales-order sequence number issued so far; the next order suggested in the create form
     * gets {@code lastSalesOrderNumber + 1} behind a fixed {@code "SO-"} prefix. Nullable (treated as
     * {@code 0}) for the same migration reason as {@link #lastInvoiceNumber}.
     */
    private Integer lastSalesOrderNumber;

    /**
     * Highest purchase-order sequence number issued so far; the next order suggested in the create form
     * gets {@code lastPurchaseOrderNumber + 1} behind a fixed {@code "PO-"} prefix. Nullable (treated as
     * {@code 0}) for the same migration reason as {@link #lastSalesOrderNumber}.
     */
    private Integer lastPurchaseOrderNumber;

    /**
     * Prefix for suggested tender numbers. Unlike the fixed {@code "SO-"}/{@code "PO-"} order prefixes this
     * is configurable, like {@link #invoiceNumberPrefix} - tender references usually have to match a
     * customer's or authority's own scheme. Nullable with an effective getter so the column adds cleanly
     * under {@code ddl-auto=update}.
     */
    private String tenderNumberPrefix;

    /**
     * Highest tender sequence number issued so far; the next tender suggested in the create form gets
     * {@code lastTenderNumber + 1}. Nullable (treated as {@code 0}) for the same migration reason as
     * {@link #lastSalesOrderNumber}.
     */
    private Integer lastTenderNumber;

    /** Effective tender-number prefix: a row that predates this column reads back null. */
    public String getTenderNumberPrefix() {
        return tenderNumberPrefix != null && !tenderNumberPrefix.isBlank() ? tenderNumberPrefix : "TND-";
    }

    /** Default number of days a customer has to pay an invoice before it is overdue. */
    @Min(0)
    @Column(nullable = false)
    private Integer invoicePaymentTermDays = 14;

    /** Penalty charged on an overdue invoice, as a percentage. */
    @DecimalMin("0.0")
    @Column(nullable = false, precision = 6, scale = 3)
    private BigDecimal latePaymentPenaltyPercent = BigDecimal.ZERO;

    /** How the {@link #latePaymentPenaltyPercent} accrues once an invoice is overdue. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PenaltyPeriod penaltyPeriod = PenaltyPeriod.DAILY;

    /** Default prepayment required to confirm an order, as a percentage of the order total. */
    @DecimalMin("0.0")
    @Column(nullable = false, precision = 6, scale = 3)
    private BigDecimal defaultPrepaymentPercent = BigDecimal.ZERO;

    // --- Seller details (printed on the invoice header) ------------------------------------------
    // The buyer side comes from the order's Client; these are the company's own details. They live here
    // rather than on Company because this is the row already edited through /api/settings, and Company
    // currently has no write endpoint.

    /** Company postal address shown in the invoice's "from" block. */
    private String companyAddress;

    /** Company contact email shown on the invoice. */
    private String companyEmail;

    /** Company contact phone shown on the invoice. */
    private String companyPhone;

    /** VAT / tax registration number shown on the invoice. */
    private String vatNumber;

    /** Bank name for the payment details block. */
    private String bankName;

    /** Bank IBAN for the payment details block. */
    private String bankIban;

    /** S3 key of the company logo (from the image upload endpoint), resolved to a presigned URL on read. */
    private String logoKey;

    // --- Invoice appearance ----------------------------------------------------------------------
    // Which PDF layout to render and what to show on it. All columns are nullable, with the effective
    // default applied in the getter, so they can be added to an existing settings row under
    // ddl-auto=update (no NOT NULL column added to a populated table) - the same migration-friendly
    // pattern used by the sequence-number fields above.

    /** Layout used to render the invoice PDF. Defaults to {@link InvoiceTemplate#CLASSIC}. */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private InvoiceTemplate invoiceTemplate;

    /** Accent colour (hex, e.g. {@code #0f766e}) applied to headings and rules across every layout. */
    @Column(length = 16)
    private String invoiceAccentColor;

    /** Whether the company logo is shown in the header (falls back to the company name when off). */
    private Boolean invoiceShowLogo;

    /** Whether the per-line SKU column is shown in the line-items table. */
    private Boolean invoiceShowLineSku;

    /** Whether the payment-terms block (due date + late penalty) is shown. */
    private Boolean invoiceShowPaymentTerms;

    /** Whether the bank-details block is shown. */
    private Boolean invoiceShowBankDetails;

    /** Whether the invoice's additional-information notes are shown. */
    private Boolean invoiceShowNotes;

    /** Custom footer line; falls back to a generic "Generated by Skladdo" line when blank. */
    private String invoiceFooterText;

    // Effective-value getters: a settings row migrated from before these columns existed reads back
    // null, so coalesce to the same defaults a freshly created row would carry. They are deliberately
    // named with the get-prefix and a primitive boolean return type: this both suppresses the raw
    // (nullable) accessors Lombok's @Getter would otherwise generate for these wrapper fields, and makes
    // them the canonical JavaBeans read method so property access (Thymeleaf, the DTO mapper) goes
    // through the coalescing logic rather than the raw field.

    public InvoiceTemplate getInvoiceTemplate() {
        return invoiceTemplate != null ? invoiceTemplate : InvoiceTemplate.CLASSIC;
    }

    public String getInvoiceAccentColor() {
        return invoiceAccentColor != null && !invoiceAccentColor.isBlank() ? invoiceAccentColor : "#0f766e";
    }

    public boolean getInvoiceShowLogo() {
        return invoiceShowLogo == null || invoiceShowLogo;
    }

    public boolean getInvoiceShowLineSku() {
        return invoiceShowLineSku == null || invoiceShowLineSku;
    }

    public boolean getInvoiceShowPaymentTerms() {
        return invoiceShowPaymentTerms == null || invoiceShowPaymentTerms;
    }

    public boolean getInvoiceShowBankDetails() {
        return invoiceShowBankDetails == null || invoiceShowBankDetails;
    }

    public boolean getInvoiceShowNotes() {
        return invoiceShowNotes == null || invoiceShowNotes;
    }

    // --- Email / SMTP ----------------------------------------------------------------------------
    // Per-company outbound mail settings, used to build a JavaMailSenderImpl per send. All nullable so
    // the columns migrate cleanly onto an existing settings row under ddl-auto=update. The password is
    // stored encrypted (never plaintext); see EncryptionService and CompanySettingsService.

    /** SMTP server hostname (e.g. "smtp.gmail.com"). */
    private String smtpHost;

    /** SMTP server port (e.g. 587 for STARTTLS). */
    private Integer smtpPort;

    /** SMTP authentication username. */
    private String smtpUsername;

    /**
     * AES-GCM-encrypted SMTP password (Base64 of IV+ciphertext+tag), never the raw password. The
     * {@code Encrypted} suffix is deliberate: nothing should read or serialize this as plaintext.
     */
    private String smtpPasswordEncrypted;

    /** Address outgoing mail is sent From (the tenant's own sending address). */
    private String smtpFromAddress;

    /** Display name shown alongside the From address. */
    private String smtpFromName;

    /** Whether to use STARTTLS. Nullable for migration; defaults to true via {@link #getSmtpUseTls()}. */
    private Boolean smtpUseTls;

    /** Effective STARTTLS flag: a row migrated from before this column existed reads back null → true. */
    public boolean getSmtpUseTls() {
        return smtpUseTls == null || smtpUseTls;
    }

    // --- New-product defaults --------------------------------------------------------------------

    /** Unit applied to a new product when none is supplied (e.g. "pcs"). */
    @Column(nullable = false)
    private String defaultProductUnit = "pcs";

    /** Minimum stock applied to a new product when none is supplied. */
    @Min(0)
    @Column(nullable = false)
    private Integer defaultMinimumStock = 0;

    // --- New-user defaults -----------------------------------------------------------------------

    /**
     * Interface language a newly invited user starts in (and the language their invitation email is
     * written in). Nullable with an effective getter so the column adds cleanly under
     * {@code ddl-auto=update}; each user can change their own afterwards.
     */
    @Column(length = 5)
    private String defaultUserLanguage;

    /**
     * Effective new-user language, falling back to English when unset or not one the app ships.
     *
     * <p>The null check is load-bearing: {@code List.of(...)} rejects a null argument to
     * {@code contains} with a {@link NullPointerException} rather than returning false, and this column
     * reads back null on every row that predates it.</p>
     */
    public String getDefaultUserLanguage() {
        return defaultUserLanguage != null && SUPPORTED_LANGUAGES.contains(defaultUserLanguage)
                ? defaultUserLanguage : "en";
    }

    // --- Order defaults --------------------------------------------------------------------------

    /**
     * Warehouse pre-selected on new sales/purchase orders. A plain id (not a mapped relation) so it
     * follows the same lightweight, migration-friendly pattern as the other defaults here; the column
     * is added to an existing settings row under {@code ddl-auto=update}. {@code null} means "no
     * default" - the order form then falls back to auto-selecting the only warehouse, if there is one.
     */
    private Long defaultWarehouseId;

    // --- Auditing --------------------------------------------------------------------------------

    @LastModifiedDate
    private Instant updatedAt;

    @LastModifiedBy
    private Long updatedById;
}
