package com.example.skladdo.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Setter
@Table(name = "tenders")
public class Tender {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    private String title;
    private String tenderNumber;
    private String status;
    private LocalDate publishedAt;
    private LocalDate deadline;

    @Column(length = 4000)
    private String description;

    private Double estimatedValue;

    /**
     * ISO 4217 currency code the {@link #estimatedValue} (and participant bids) are quoted in. Nullable;
     * a {@code null} value is treated as the company base currency, which the service stamps on new tenders.
     */
    @Column(length = 3)
    private String currency;

    /**
     * Exchange rate snapshotted when the tender was saved: units of {@link #currency} per 1 unit of the
     * company base currency ({@code 1 base = rate foreign}). Dividing an amount in {@link #currency} by
     * this gives the company-currency equivalent. Null/1 when the tender is already in the base currency.
     */
    @Column(precision = 19, scale = 6)
    private BigDecimal exchangeRate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    private Client client;

    @JsonIgnore
    @OneToMany(mappedBy = "tender", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TenderParticipant> participants = new ArrayList<>();

    /** The tender's lots. Every tender has at least one (auto-created); shown in {@code sortOrder}. */
    @JsonIgnore
    @OneToMany(mappedBy = "tender", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder asc, id asc")
    private List<TenderPart> parts = new ArrayList<>();
}