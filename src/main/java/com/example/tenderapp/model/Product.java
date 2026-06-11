package com.example.tenderapp.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;

@Entity
@Getter
@Setter
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

    private String imageUrl;

    @NotNull
    @DecimalMin("0.0")
    @Column(nullable = false)
    private BigDecimal price;

    @NotNull
    @Min(0)
    @Column(nullable = false)
    private Integer stockQuantity = 0;

    @NotNull
    @Min(0)
    @Column(nullable = false)
    private Integer minimumStock = 0;

    private Boolean active = true;
}