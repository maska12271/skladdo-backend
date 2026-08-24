package com.example.skladdo.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;

/**
 * One line of an {@link Invoice}, snapshotted from a {@link SalesOrderItem} at generation time. Unlike a
 * sales order line - which reads the product name/sku/price live from {@link Product} - an invoice line
 * freezes those display values so the issued document never changes if the product is later renamed,
 * repriced, or deleted. The {@link #product} link is kept only for an optional "view product" navigation.
 *
 * <p>A line can also come from a {@link Service}, in which case {@link #product} is null and
 * {@link #productName}/{@link #sku} hold the service's name and code. They are display text rather
 * than a product reference, so they serve both without a parallel pair of columns - the same reason
 * {@code InvoiceService.buildPrepayment} can put a free-text label through them.</p>
 */
@Entity
@Getter
@Setter
public class InvoiceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    @ManyToOne
    @JoinColumn(nullable = false)
    private Invoice invoice;

    @ManyToOne
    @JoinColumn(name = "product_id")
    private Product product;

    private String productName;

    private String sku;

    private Integer quantity;

    private BigDecimal unitPrice;

    /** Per-line discount percentage, snapshotted from the order line. */
    private BigDecimal discountPercent;

    /** Tax rate shown for this line, snapshotted from the order line. */
    private BigDecimal taxRatePercent;

    /** Net line amount after discount, before tax. */
    private BigDecimal lineTotal;
}
