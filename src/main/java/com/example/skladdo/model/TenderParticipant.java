package com.example.skladdo.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

/**
 * A company taking part in a tender <em>part</em> - either a competitor we are tracking or our own company
 * ({@link #ownCompany}). {@link #participating} records whether they actually bid on this part; at most one
 * participant per part is the {@link #winner}. Our own company is auto-created as a non-deletable row on
 * every part so "who is participating" and "who won" use one unified list for us and competitors alike.
 *
 * <p>Belongs to a {@link #part}; {@link #tender} is kept as a denormalised convenience/back-reference (and
 * so legacy rows created before parts existed still delete with their tender).</p>
 */
@Entity
@Getter
@Setter
@Table(name = "tender_participants")
public class TenderParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    private String manufacturerName;

    private Double offeredPrice;

    @Column(length = 2000)
    private String notes;

    private Boolean winner = false;

    /** True for the single row that represents our own company on this part. */
    private Boolean ownCompany = false;

    /** Whether this participant actually bids on / takes part in this part. */
    private Boolean participating = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "part_id")
    @JsonIgnore
    private TenderPart part;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tender_id", nullable = false)
    @JsonIgnore
    private Tender tender;
}
