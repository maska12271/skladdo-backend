package com.example.kladdo.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * A tenant. Every business entity carries the id of the company it belongs to
 * (see Hibernate {@code @TenantId} columns), so data is fully isolated per company.
 */
@Entity
@Getter
@Setter
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false)
    private String name;

    @Column(unique = true)
    private String registrationCode;

    private Boolean active = true;

    /**
     * Which half of the app this company lives in. Chosen at signup and never editable afterwards - it is
     * a security boundary, not a preference. Nullable in the database so the column adds cleanly under
     * {@code ddl-auto=update}; every company that predates the split is a {@link CompanyType#BUSINESS} one,
     * which is what {@link #getType()} returns for null.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, updatable = false)
    private CompanyType type = CompanyType.BUSINESS;

    /** This company's kind, reading the pre-split null as {@link CompanyType#BUSINESS}. */
    public CompanyType getType() {
        return type == null ? CompanyType.BUSINESS : type;
    }

    /** True when this company only works inside its clients' companies and owns no business data itself. */
    public boolean isWarehouseAccount() {
        return getType() == CompanyType.WAREHOUSE;
    }
}
