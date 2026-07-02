package com.example.kladdo.model;

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
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Setter
@EntityListeners(AuditingEntityListener.class)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    @NotBlank
    @Column(nullable = false)
    private String name;

    @Column(unique = true)
    private String sku;

    @ManyToOne
    @JoinColumn(nullable = false)
    private Manufacturer manufacturer;

    @ManyToOne
    @JoinColumn(nullable = false)
    private Category category;

    private String size;

    private String unit;

    @Column(length = 2000)
    private String description;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "product_image", joinColumns = @JoinColumn(name = "product_id"))
    @Column(name = "image_url", length = 1000)
    private List<String> imageUrls = new ArrayList<>();

    @NotNull
    @DecimalMin("0.0")
    @Column(nullable = false)
    private BigDecimal price;

    /**
     * Tax rate applied to this product. Optional - when {@code null} the company default rate is used.
     * Prices are stored net of tax; the rate only drives tax-inclusive display and downstream invoicing.
     */
    @ManyToOne
    @JoinColumn(name = "tax_rate_id")
    private TaxRate taxRate;

    @NotNull
    @Min(0)
    @Column(nullable = false)
    private Integer stockQuantity = 0;

    @NotNull
    @Min(0)
    @Column(nullable = false)
    private Integer minimumStock = 0;

    /**
     * Lot-consumption strategy used when selling this product. Kept nullable at the DB level so the
     * column can be added to existing rows ({@code ddl-auto=update}); a {@code null} value is treated
     * as {@link WarehouseMethod#FEFO} everywhere it is read.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "warehouse_method")
    private WarehouseMethod warehouseMethod = WarehouseMethod.FEFO;

    private Boolean active = true;

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