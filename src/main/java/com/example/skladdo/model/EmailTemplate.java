package com.example.skladdo.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * A reusable email template for manufacturer outreach, authored by a tenant admin. The {@link #subject}
 * and {@link #body} may contain {@code {{token}}} placeholders (e.g. {@code {{manufacturer.name}}}) that
 * are substituted at send time by {@code EmailTemplateRenderer}. Unlike the developer-authored invoice
 * PDF templates, this text is user data and is never evaluated as an expression language.
 *
 * <p>{@code @TenantId}-scoped so each company only sees its own templates. No unique constraint on
 * {@link #name}: duplicate-named drafts are allowed.</p>
 */
@Entity
@Getter
@Setter
@EntityListeners(AuditingEntityListener.class)
public class EmailTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    @NotBlank
    @Column(nullable = false)
    private String name;

    @NotBlank
    @Column(nullable = false, length = 500)
    private String subject;

    @NotBlank
    @Column(nullable = false, length = 10000)
    private String body;

    private Boolean active = true;

    @CreatedDate
    private Instant createdAt;

    @CreatedBy
    private Long createdById;

    @LastModifiedDate
    private Instant updatedAt;

    @LastModifiedBy
    private Long updatedById;
}
