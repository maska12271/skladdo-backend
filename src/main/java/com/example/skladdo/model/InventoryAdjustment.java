package com.example.skladdo.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * A single stock-taking ("inventarization") movement: a product's on-hand quantity is changed by
 * {@link #quantityChange} (positive = added, negative = removed) with a reason {@link #note}. Each
 * adjustment records the resulting {@link #newQuantity} and who made it, forming the audit trail shown
 * on the product detail page. {@code @TenantId}-scoped and immutable once written.
 */
@Entity
@Table(name = "inventory_adjustment")
@Getter
@Setter
@EntityListeners(AuditingEntityListener.class)
public class InventoryAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    /** The warehouse in which this adjustment was applied. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", updatable = false)
    private Warehouse warehouse;

    /** Signed change applied to the product's stock: positive added, negative removed. Never zero. */
    @NotNull
    @Column(nullable = false, updatable = false)
    private Integer quantityChange;

    /** The product's stock quantity after this adjustment was applied. */
    @NotNull
    @Column(nullable = false, updatable = false)
    private Integer newQuantity;

    /** Why the adjustment was made (e.g. "stock count correction", "damaged goods"). */
    @Column(length = 1000, updatable = false)
    private String note;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(updatable = false)
    private Long createdById;
}
