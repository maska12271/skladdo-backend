package com.example.tenderapp.model;

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
}
