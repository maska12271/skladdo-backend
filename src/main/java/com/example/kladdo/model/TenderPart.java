package com.example.kladdo.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.util.ArrayList;
import java.util.List;

/**
 * One lot / part of a tender. A tender is procured in one or more parts, and a company can participate in
 * any subset of them; requirements, participants and the winner are all tracked per part. Every tender has
 * at least one part (a simple single-lot tender is just a tender with one part). All parts share the
 * tender's currency and exchange rate - only the value differs per part.
 */
@Entity
@Getter
@Setter
@Table(name = "tender_part")
public class TenderPart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tender_id", nullable = false)
    @JsonIgnore
    private Tender tender;

    /** Human label for the lot, e.g. "1" or "Lot A". */
    private String partNumber;

    private String title;

    @Column(length = 4000)
    private String description;

    /** Estimated value of this part, in the tender's currency. */
    private Double estimatedValue;

    /**
     * Whether physical samples are required for this part. When true, each requirement may carry a
     * {@link TenderRequirement#getSampleQuantity()}. Nullable (legacy rows); treat null as false.
     */
    private Boolean samplesRequired;

    /** Ordering within the tender (parts are shown/numbered in this order). */
    private int sortOrder;

    @OneToMany(mappedBy = "part", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder asc, id asc")
    private List<TenderRequirement> requirements = new ArrayList<>();

    @OneToMany(mappedBy = "part", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("ownCompany desc, id asc")
    private List<TenderParticipant> participants = new ArrayList<>();
}
