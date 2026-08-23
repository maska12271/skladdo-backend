package com.example.skladdo.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;

/**
 * A configurable tax rate (e.g. "Standard VAT" at 20%). A company can define as many as it needs and
 * mark exactly one as the {@link #isDefault default}, which applies to products that don't carry their
 * own rate. {@code @TenantId}-scoped, so each company maintains its own set.
 */
@Entity
@Getter
@Setter
public class TaxRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    @NotBlank
    @Column(nullable = false)
    private String name;

    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    @Column(nullable = false, precision = 6, scale = 3)
    private BigDecimal percentage;

    /** The rate applied to new products and to products without an explicit rate. At most one per company. */
    @Column(nullable = false)
    private boolean isDefault = false;

    @Column(nullable = false)
    private boolean active = true;
}
