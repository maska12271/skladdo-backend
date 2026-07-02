package com.example.kladdo.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A received lot of a product held in a single warehouse. Created (or topped up) when a purchase
 * order is marked SHIPPED, and drawn down — possibly across several lots — when a sales order ships.
 *
 * <p>One row represents the remaining quantity of a {@code (product, warehouse, lotNumber)} triple.
 * Re-receiving the same lot into the same warehouse adds to the existing row rather than creating a
 * duplicate. {@link WarehouseStock} remains the authoritative per-warehouse total; the batch table
 * is the lot-level detail beneath it.</p>
 */
@Entity
@Getter
@Setter
@EntityListeners(AuditingEntityListener.class)
@Table(uniqueConstraints = @UniqueConstraint(
        name = "uq_product_batch_lot",
        columnNames = {"company_id", "product_id", "warehouse_id", "lot_number"}))
public class ProductBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    @ManyToOne
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @Column(name = "lot_number", nullable = false)
    private String lotNumber;

    /** Units still on hand for this lot. */
    @Column(nullable = false)
    private Integer quantity = 0;

    /** Total ever received into this lot — for "X of Y remaining" displays. */
    @Column(nullable = false)
    private Integer originalQuantity = 0;

    private LocalDate productionDate;

    private LocalDate expiryDate;

    /** Receipt timestamp — drives FIFO/LIFO ordering and the FEFO tiebreak. */
    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;
}
