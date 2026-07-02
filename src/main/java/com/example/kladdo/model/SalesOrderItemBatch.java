package com.example.kladdo.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.LocalDate;

/**
 * Records that a sales-order line consumed a given quantity from a specific {@link ProductBatch}.
 * One sales line can have several of these when its quantity is split across lots (e.g. 100 from
 * lot A + 200 from lot B). The lot's identifying details are snapshotted here so the order detail
 * view stays accurate even if the underlying batch is later depleted or removed.
 */
@Entity
@Getter
@Setter
public class SalesOrderItemBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    @ManyToOne
    @JoinColumn(nullable = false)
    @JsonBackReference("item-batches")
    private SalesOrderItem salesOrderItem;

    /** The lot drawn from. Kept for reversal (restoring stock); not serialized to avoid deep graphs. */
    @ManyToOne
    @JoinColumn(name = "product_batch_id")
    @JsonIgnore
    private ProductBatch productBatch;

    /** Snapshot of the lot number at allocation time. */
    private String lotNumber;

    private LocalDate productionDate;

    private LocalDate expiryDate;

    private Integer quantityUsed;
}
