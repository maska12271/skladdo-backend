package com.example.kladdo.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Setter
public class SalesOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    @ManyToOne
    @JoinColumn(nullable = false)
    private Product product;

    @ManyToOne
    @JoinColumn(nullable = false)
    @JsonBackReference
    private SalesOrder salesOrder;

    private Integer quantity;

    private BigDecimal unitPrice;

    /** Per-line discount as a percentage of {@code unitPrice * quantity}. Null/0 means no discount. */
    private BigDecimal discountPercent;

    /** Tax rate applied to this line, as a percentage. Snapshotted from the product's rate (editable). */
    private BigDecimal taxRatePercent;

    /** Net line amount after discount, before tax: {@code unitPrice * quantity * (1 - discount/100)}. */
    private BigDecimal lineTotal;

    /** Which lots this line drew from when it shipped (one entry per lot; several when split). */
    @OneToMany(mappedBy = "salesOrderItem", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonManagedReference("item-batches")
    private List<SalesOrderItemBatch> batchAllocations = new ArrayList<>();
}