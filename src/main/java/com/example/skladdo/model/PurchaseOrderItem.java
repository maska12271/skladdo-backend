package com.example.skladdo.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
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

    /**
     * How many units of this line have physically arrived, recorded at goods-receipt. Purely progress
     * tracking: it never moves stock (stock still moves when the order reaches a stock-affecting status),
     * so a receipt can be recorded and corrected while a delivery is being checked in.
     *
     * <p>Deliberately <em>not</em> capped at {@link #quantity}: suppliers over-deliver, and hiding that
     * would defeat the point of checking a delivery in. The UI flags any mismatch.</p>
     *
     * <p>Nullable - a line that predates this feature, or has not been received, reads back {@code null},
     * treated as 0 everywhere.</p>
     */
    @Min(0)
    private Integer receivedQuantity;

    private BigDecimal unitPrice;

    private BigDecimal lineTotal;

    /** Lot identifier for the goods received on this line. Becomes a {@link ProductBatch} on SHIPPED. */
    private String lotNumber;

    private LocalDate productionDate;

    private LocalDate expiryDate;
}