package com.example.skladdo.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.annotations.TenantId;
import lombok.Getter;
import lombok.Setter;

/**
 * A named person at a client or a manufacturer - who to actually talk to, as opposed to the company's
 * main address.
 *
 * <p>One row per person, so a partner can have as many as it really has: a buyer, someone in accounts,
 * someone on the loading bay. That is the point of the table. {@link Client} used to carry a single
 * {@code contactPerson} string, which could hold one name and no way to reach them; those were migrated
 * into rows here and the column dropped (see {@code SchemaMigrations}). The partner's own
 * {@code email}/{@code phone} stay where they are - they are the company's, not a person's.</p>
 *
 * <p><strong>One owner, never both.</strong> {@link #clientId} and {@link #manufacturerId} are a
 * this-or-that pair and exactly one is set, which {@code PartnerContactService} is the only writer of
 * and therefore the thing that enforces. Modelled as plain ids rather than {@code @ManyToOne} for the
 * same reason {@code PurchaseOrder.tenderId} is: the owning entities are serialized straight to JSON, and
 * a mapped association here would have to be a bidirectional one to be useful, which is a cycle to break
 * and a fetch to tune for no gain at this size.</p>
 */
@Entity
@Table(name = "partner_contact", indexes = {
        @Index(name = "idx_partner_contact_client", columnList = "client_id"),
        @Index(name = "idx_partner_contact_manufacturer", columnList = "manufacturer_id")
})
@Getter
@Setter
public class PartnerContact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    /** The client this person works for, or {@code null} when they work for a manufacturer. */
    @Column(name = "client_id", updatable = false)
    private Long clientId;

    /** The manufacturer this person works for, or {@code null} when they work for a client. */
    @Column(name = "manufacturer_id", updatable = false)
    private Long manufacturerId;

    @NotBlank
    @Column(nullable = false)
    private String name;

    /** Their job there - "Purchasing", "Accounts" - so a list of names is possible to choose between. */
    @Column(length = 120)
    private String position;

    /**
     * Where to write to them. Optional: a contact worth recording is not always one you have an address
     * for, and the ones you do have addresses for are the ones the compose form offers.
     */
    @Email
    private String email;
}
