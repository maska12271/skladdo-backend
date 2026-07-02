package com.example.kladdo.model;

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

    /** URL of the company logo (an /uploads/* path from the image upload endpoint), shown in the header. */
    private String logoUrl;

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

    /** Custom footer line; falls back to a generic "Generated by Kladdo" line when blank. */
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

    // --- New-product defaults --------------------------------------------------------------------

    /** Unit applied to a new product when none is supplied (e.g. "pcs"). */
    @Column(nullable = false)
    private String defaultProductUnit = "pcs";

    /** Minimum stock applied to a new product when none is supplied. */
    @Min(0)
    @Column(nullable = false)
    private Integer defaultMinimumStock = 0;

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
