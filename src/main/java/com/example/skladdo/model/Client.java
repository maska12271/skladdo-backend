package com.example.skladdo.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

@Entity
@Getter
@Setter
public class Client {

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
    private String registrationCode;

    @Email
    private String email;

    private String phone;

    private String country;

    private String address;

    // The single contactPerson string that used to live here is now a PartnerContact row (or several) -
    // one name with no way to reach them was never enough. SchemaMigrations moved the existing values.

    @Column(length = 2000)
    private String notes;

    private Boolean active = true;

    /**
     * Archived clients are hidden from the default client list (and therefore from the order/tender
     * pickers) but keep all their history. The soft alternative to deleting a client that already has
     * orders, tenders or invoices referencing it. Nullable so the column can be added to existing rows
     * under {@code ddl-auto=update}; a {@code null} value is treated as "not archived".
     */
    private Boolean archived = false;
}