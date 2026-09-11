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

    /** VAT / tax registration number (e.g. {@code EE100705445}), used as the buyer's VAT id on e-invoices. */
    private String vatNumber;

    /**
     * Department identifier this buyer requires on invoices sent to them, emitted as {@code BuyerParty/DepId}
     * in the Estonian e-invoice XML. Free text handed to us by the buyer (e.g. {@code "Sales dept."}), used by
     * their accounting system to route the invoice internally - larger and public-sector buyers commonly
     * insist on it. Null for the majority of clients, who do not use one.
     */
    private String invoiceDepartmentId;

    @Email
    private String email;

    private String phone;

    private String country;

    // --- Postal address -------------------------------------------------------------------------
    // Stored in parts rather than as one line: the e-invoice schema needs the street and the city as a
    // mandatory pair, and a single free-text line cannot be split back apart reliably. The old `address`
    // column was divided into these by SchemaMigrations and then dropped.

    /** Street, house and apartment - the e-invoice's {@code PostalAddress1}. */
    private String addressStreet;

    /** City or county - the e-invoice's {@code City}. */
    private String addressCity;

    @Column(length = 10)
    private String addressPostalCode;

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

    /**
     * The address parts as one printable line. Not a column - it replaces the free-text {@code address}
     * field this entity used to store, so everything that only ever wanted something to display (the
     * invoice PDF, email tokens, a delivery address copied off the client) keeps reading {@code address}
     * unchanged. Read-only: Jackson has no setter to bind to, so writers must send the parts.
     */
    public String getAddress() {
        return PostalAddress.compose(addressStreet, addressPostalCode, addressCity);
    }
}