package com.example.skladdo.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

/**
 * A single thing a tender part is looking for - one line of the part's requirements/specification (e.g.
 * "50 laptops, 16GB RAM"). A part can list as many requirements as it needs.
 */
@Entity
@Getter
@Setter
@Table(name = "tender_requirement")
public class TenderRequirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "part_id", nullable = false)
    @JsonIgnore
    private TenderPart part;

    /** What is required (free text). */
    @Column(length = 2000, nullable = false)
    private String description;

    /**
     * Optionally links this requirement to a {@link Service} from the catalogue. Purely a reference
     * alongside the free-text {@link #description}, which stays the authoritative wording of what the
     * tender asks for - a requirement is often phrased in the buyer's terms rather than ours, and many
     * never correspond to a catalogue entry at all.
     */
    @ManyToOne
    @JoinColumn(name = "service_id")
    private Service service;

    /** Optional required quantity. */
    private Double quantity;

    /** Optional unit for the quantity, e.g. "pcs", "kg". */
    private String unit;

    /**
     * Optional number of physical samples required for this item. Only meaningful when the owning
     * {@link TenderPart#getSamplesRequired()} is true; nulled out otherwise.
     */
    private Integer sampleQuantity;

    /** Ordering within the part. */
    private int sortOrder;
}
