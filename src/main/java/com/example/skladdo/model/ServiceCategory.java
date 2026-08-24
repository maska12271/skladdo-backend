package com.example.skladdo.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

/**
 * The taxonomy for {@link Service}s, deliberately separate from {@link Category} (which classifies
 * physical products): "Consulting" and "Installation" have no business sitting in the same picker as
 * "Electronics". Flat, like {@link Category} - there is no nesting.
 */
@Entity
@Getter
@Setter
public class ServiceCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    @NotBlank
    @Column(nullable = false)
    private String name;

    private String description;

    private Boolean active = true;
}
