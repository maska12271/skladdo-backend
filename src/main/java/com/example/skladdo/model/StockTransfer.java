package com.example.skladdo.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * A movement of stock for a single product from one warehouse to another. Unlike an
 * {@link InventoryAdjustment} (which changes the company-wide on-hand total), a transfer is net-zero:
 * it decrements {@link WarehouseStock} in the source warehouse and increments it in the destination,
 * optionally moving a specific {@link ProductBatch} lot so its production/expiry identity is preserved.
 *
 * <p>Immutable once written; forms the transfer audit trail shown on the product detail page.
 * {@code @TenantId}-scoped so each company only sees its own movements.</p>
 */
@Entity
@Table(name = "stock_transfer")
@Getter
@Setter
@EntityListeners(AuditingEntityListener.class)
public class StockTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "from_warehouse_id", nullable = false, updatable = false)
    private Warehouse fromWarehouse;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "to_warehouse_id", nullable = false, updatable = false)
    private Warehouse toWarehouse;

    /** Snapshot of the lot moved, when a specific lot was chosen; null for a flat (untracked) move. */
    @Column(updatable = false)
    private String lotNumber;

    /** Units moved. Always positive. */
    @Column(nullable = false, updatable = false)
    private Integer quantity;

    /** Optional free-text reason for the move. */
    @Column(length = 1000, updatable = false)
    private String note;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(updatable = false)
    private Long createdById;
}
