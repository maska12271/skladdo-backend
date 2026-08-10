package com.example.kladdo.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The last exchange rate the company actually used for a currency - a per-tenant cache, one row per
 * currency, upserted whenever an order/tender is saved with a rate. It is <em>not</em> user-managed: it
 * exists so that when the European Central Bank feed has no rate for a currency (e.g. RUB), the currency
 * picker can fall back to "the rate you used last time" instead of forcing a fresh manual entry.
 *
 * <p>{@link #rate} follows the same orientation as the rate stored on a transaction: how many units of
 * {@link #currencyCode} one unit of the company base currency ({@link CompanySettings#getCurrency()}) buys
 * - i.e. {@code 1 base = rate foreign}. {@code @TenantId}-scoped, so each company keeps its own cache.</p>
 */
@Entity
@Getter
@Setter
@Table(name = "exchange_rate")
@EntityListeners(AuditingEntityListener.class)
public class ExchangeRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    /** ISO 4217 code of the foreign currency this rate is for (e.g. "USD"). One row per currency. */
    @NotBlank
    @Size(min = 3, max = 3)
    @Column(nullable = false, length = 3)
    private String currencyCode;

    /** Units of {@link #currencyCode} per 1 unit of the company base currency ({@code 1 base = rate foreign}). */
    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal rate;

    /** The date this cached rate was last recorded for. */
    @NotNull
    @Column(nullable = false)
    private LocalDate asOfDate;

    /** Where the rate came from when it was cached, e.g. "ECB" or "MANUAL" (free-text; null until set). */
    private String source;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @CreatedBy
    @Column(updatable = false)
    private Long createdById;

    @LastModifiedBy
    private Long updatedById;
}
