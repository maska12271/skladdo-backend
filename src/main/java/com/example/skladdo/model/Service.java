package com.example.skladdo.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

/**
 * Something the company sells or buys that has a price but no physical existence - installation,
 * consulting hours, a delivery fee. The catalogue sibling of {@link Product}, minus everything
 * stock-shaped: no quantity, no warehouse, no lots, no reorder point, and no manufacturer.
 *
 * <p>A service reaches a document by being referenced from a {@link SalesOrderItem} or a
 * {@link TenderRequirement}. Nothing in the warehouse subsystem (stock ledger, batches, transfers,
 * reorder suggestions) ever sees one: those all key strictly on {@code product.id}, so a line whose
 * product is null is invisible to them by construction rather than by a guard.</p>
 *
 * <p><strong>Note on the class name.</strong> This collides with Spring's {@code @Service}
 * stereotype, so the handful of {@code @Service}-annotated classes that reference this entity
 * qualify the annotation as {@code @org.springframework.stereotype.Service} rather than rename the
 * domain concept.</p>
 */
@Entity
// Scoped to the company, unlike Product.sku's plain unique column - two tenants must be able to use
// the same code.
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "code"}))
@Getter
@Setter
@EntityListeners(AuditingEntityListener.class)
public class Service {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    @NotBlank
    @Column(nullable = false)
    private String name;

    /** Short identifier, the counterpart of {@link Product#getSku()}. Optional; blank is stored as null. */
    private String code;

    /**
     * Optional, unlike {@link Product#getCategory()}. A services list is typically short enough that
     * forcing a taxonomy pick before the first service can be saved is friction with nothing to show
     * for it; an uncategorised service is a perfectly ordinary thing to have.
     */
    @ManyToOne
    @JoinColumn(name = "service_category_id")
    private ServiceCategory category;

    @Column(length = 2000)
    private String description;

    @NotNull
    @DecimalMin("0.0")
    @Column(nullable = false)
    private BigDecimal price;

    /**
     * ISO 4217 currency code the {@link #price} is quoted in. Null is treated as the company base
     * currency everywhere it is read; the service stamps the base currency on new services.
     */
    @Column(length = 3)
    private String currency;

    /** Optional - when null the company default rate is used. Prices are stored net of tax. */
    @ManyToOne
    @JoinColumn(name = "tax_rate_id")
    private TaxRate taxRate;

    private Boolean active = true;

    /**
     * How often this service is expected to recur, in months (e.g. {@code 6} for an oil change). Null
     * means one-time - deliberately no separate boolean flag, the same reasoning that kept an
     * {@code itemType} discriminator off {@link SalesOrderItem}: a second field just to say what this
     * one's nullness already says would be a second thing to keep in sync.
     */
    @Min(1)
    private Integer recurrenceMonths;

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
}
