package com.example.skladdo.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A PDF invoice issued for a {@link SalesOrder}. Once generated, an invoice is a financial document: its
 * money fields, payment terms and buyer details are <em>snapshots</em> taken at generation time, so a
 * later edit to the order, the client, or the company settings never changes an already-issued invoice.
 * Corrections are made by voiding and regenerating, never by editing in place.
 *
 * <p>{@code @TenantId}-scoped like the rest of the domain, and audited the same way as
 * {@link SalesOrder}. Whether the invoice is overdue and how large its late-payment penalty is are
 * derived live from {@link #dueDate} while {@link #status} is {@code UNPAID} - see
 * {@code PenaltyCalculator} - and only frozen into {@link #penaltyAmountCharged} when it is marked paid.</p>
 */
@Entity
@Getter
@Setter
@EntityListeners(AuditingEntityListener.class)
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    @Column(unique = true)
    private String invoiceNumber;

    @ManyToOne
    @JoinColumn(nullable = false)
    private SalesOrder salesOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoicePaymentStatus status = InvoicePaymentStatus.UNPAID;

    /**
     * Whether this is the final invoice for the order or an up-front prepayment. Nullable at the DB level
     * (read back as {@link InvoiceType#FINAL} by {@link #getType()}) so the column migrates cleanly onto
     * an existing table under {@code ddl-auto=update}.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private InvoiceType type;

    private LocalDate issueDate;

    private LocalDate dueDate;

    // --- Money snapshot (copied from the sales order + settings at generation time) --------------

    @Column(nullable = false, length = 3)
    private String currency = "EUR";

    @Column(nullable = false)
    private BigDecimal subtotalAmount = BigDecimal.ZERO;

    /** Sum of per-line tax, snapshotted from the order. */
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal deliveryPrice = BigDecimal.ZERO;

    /** Gross amount due: {@link #subtotalAmount} + {@link #taxAmount} + {@link #deliveryPrice}. */
    @Column(nullable = false)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    // --- Penalty terms snapshot ------------------------------------------------------------------

    /** Late-payment penalty percentage in force when this invoice was issued. */
    @Column(nullable = false, precision = 6, scale = 3)
    private BigDecimal penaltyPercent = BigDecimal.ZERO;

    /** How {@link #penaltyPercent} accrues once overdue, in force when this invoice was issued. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PenaltyPeriod penaltyPeriod = PenaltyPeriod.DAILY;

    // --- Prepayment ------------------------------------------------------------------------------

    /** For a {@link InvoiceType#PREPAYMENT} invoice, the share of the order it represents (display only). */
    @Column(precision = 6, scale = 3)
    private BigDecimal prepaymentPercent;

    /**
     * For a {@link InvoiceType#FINAL} invoice, the paid prepayment invoice whose amount is deducted from
     * the balance due. Null when there is no prepayment to net off. Self-reference kept so the PDF can
     * name the prepayment; the amount itself is frozen in {@link #appliedPrepaymentAmount}.
     */
    @ManyToOne
    @JoinColumn(name = "applied_prepayment_invoice_id")
    private Invoice appliedPrepaymentInvoice;

    /** Amount deducted for {@link #appliedPrepaymentInvoice}, frozen at generation time. Null when none. */
    private BigDecimal appliedPrepaymentAmount;

    // --- Payment --------------------------------------------------------------------------------

    private LocalDate paidDate;

    /** Penalty owed at the moment of payment, frozen so the figure stops climbing once paid. Null while unpaid. */
    private BigDecimal penaltyAmountCharged;

    // --- Buyer snapshot (the order's client link can be repointed later; the invoice must not change) -

    @ManyToOne
    @JoinColumn(name = "client_id")
    private Client client;

    private String clientName;

    private String clientAddress;

    private String clientRegistrationCode;

    private String clientEmail;

    // --- Document -------------------------------------------------------------------------------

    /** Additional information carried over from the order, shown on the invoice PDF. */
    @Column(length = 2000)
    private String notes;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<InvoiceItem> items = new ArrayList<>();

    // --- Auditing -------------------------------------------------------------------------------

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @CreatedBy
    @Column(updatable = false)
    private Long createdById;

    @LastModifiedBy
    private Long updatedById;

    // Effective-value getter: a row migrated from before the type column existed reads back null, so
    // coalesce to FINAL. Named with the get-prefix and non-null return so it becomes the canonical
    // JavaBeans read method (Thymeleaf, the DTO mapper) rather than Lombok's raw nullable accessor.
    public InvoiceType getType() {
        return type != null ? type : InvoiceType.FINAL;
    }

    /** Convenience for templates and services: whether this invoice is an up-front prepayment. */
    public boolean isPrepayment() {
        return getType() == InvoiceType.PREPAYMENT;
    }
}
