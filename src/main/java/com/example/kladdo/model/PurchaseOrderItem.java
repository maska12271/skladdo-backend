package com.example.kladdo.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Getter
@Setter
public class PurchaseOrderItem {

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
    private PurchaseOrder purchaseOrder;

    private Integer quantity;

    private BigDecimal unitPrice;

    private BigDecimal lineTotal;

    /** Lot identifier for the goods received on this line. Becomes a {@link ProductBatch} on SHIPPED. */
    private String lotNumber;

    private LocalDate productionDate;

    private LocalDate expiryDate;
}