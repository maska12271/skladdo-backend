package com.example.tenderapp.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * A login account belonging to a {@link Company}. Email is unique across the whole
 * system so it can be used as the login identifier before a tenant is known.
 *
 * <p>This entity is intentionally <em>not</em> tenant-scoped via {@code @TenantId} - it must be
 * loadable during authentication (before the current company is established). User listings are
 * scoped to the caller's company manually in the service layer.</p>
 */
@Entity
@Table(name = "app_user")
@Getter
@Setter
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Email
    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.USER;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    private Boolean active = true;

    private Boolean archived = false;
}
