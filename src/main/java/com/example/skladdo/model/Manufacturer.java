package com.example.skladdo.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.util.HashSet;
import java.util.Set;

@Entity
@Getter
@Setter
public class Manufacturer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    @NotBlank
    @Column(nullable = false, unique = true)
    private String name;

    private String country;

    private String address;

    @Email
    private String email;

    private String phone;

    private String website;

    @Column(length = 2000)
    private String notes;

    /**
     * Partner categories this manufacturer belongs to. Eagerly loaded because the API serializes
     * manufacturers outside an open session ({@code spring.jpa.open-in-view=false}).
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "manufacturer_category",
            joinColumns = @JoinColumn(name = "manufacturer_id"),
            inverseJoinColumns = @JoinColumn(name = "category_id")
    )
    private Set<PartnerCategory> categories = new HashSet<>();

    private Boolean active = true;
}