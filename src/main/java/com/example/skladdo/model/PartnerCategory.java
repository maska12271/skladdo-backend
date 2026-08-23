package com.example.skladdo.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

/**
 * A category for business partners — clients (buyers) and manufacturers (suppliers). Deliberately
 * separate from {@link Category}, which classifies products: the two taxonomies are managed on their
 * own pages and never share rows. A client or manufacturer can carry several of these at once
 * (a many-to-many, owned by {@link Client} / {@link Manufacturer}).
 */
@Entity
@Getter
@Setter
public class PartnerCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    @NotBlank
    @Column(nullable = false, unique = true)
    private String name;

    private String description;

    private Boolean active = true;
}
