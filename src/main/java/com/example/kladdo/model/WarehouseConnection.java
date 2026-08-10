package com.example.kladdo.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A live link between a {@link CompanyType#WAREHOUSE} account and the {@link CompanyType#BUSINESS} company
 * it works for. While one is {@link ConnectionStatus#ACTIVE}, the warehouse company's users may switch
 * their active company to {@code clientCompanyId} and work inside that tenant - which is what lets one
 * logistics login serve many clients without an account per client.
 *
 * <p>Connections run in <strong>one direction only</strong>: the client issues a {@link ConnectionCode}
 * and the warehouse company redeems it, which activates the link immediately. There is no request to
 * approve, so a connection is never anything but active or revoked.</p>
 *
 * <p>The stock never moves and is never mirrored. {@link #getWarehouseIds()} names which of the
 * <em>client's own</em> warehouses this partner may touch - the client picks them, and everything booked
 * against those warehouses stays the client's throughout.</p>
 *
 * <p><strong>Deliberately not {@code @TenantId}-scoped.</strong> The row belongs to two tenants at once,
 * so a discriminator would hide it from one of them. Like {@link Company} and {@link User} it is queried
 * by explicit company id instead - every repository method here names the side it is querying for.</p>
 */
@Entity
@Table(
        name = "warehouse_connection",
        indexes = {
                @Index(name = "idx_wh_conn_warehouse", columnList = "warehouse_company_id"),
                @Index(name = "idx_wh_conn_client", columnList = "client_company_id")
        }
)
@Getter
@Setter
public class WarehouseConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The warehouse account doing the work. Its users are the ones who gain access. */
    @Column(name = "warehouse_company_id", nullable = false, updatable = false)
    private Long warehouseCompanyId;

    /** The business company whose data is being opened up. */
    @Column(name = "client_company_id", nullable = false, updatable = false)
    private Long clientCompanyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConnectionStatus status = ConnectionStatus.ACTIVE;

    /**
     * Whether the partner's staff may see monetary values in this client's data. Controlled by the client
     * (it is their commercial data), default off - the same choice {@link User#getCanSeePrices()} offers
     * for a company's own warehouse staff.
     */
    @Column(nullable = false)
    private boolean canSeePrices = false;

    /**
     * Which of the client's warehouses this partner may work in. Chosen by the client and empty until they
     * assign one, so redeeming a code grants entry to the company but not yet to any stock.
     *
     * <p>Eager because the set is a handful of ids and its only readers - the per-request access check and
     * the connections list - run outside a transaction.</p>
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "connection_warehouse",
            joinColumns = @JoinColumn(name = "connection_id"),
            indexes = @Index(name = "idx_conn_wh_connection", columnList = "connection_id")
    )
    @Column(name = "warehouse_id", nullable = false)
    private Set<Long> warehouseIds = new LinkedHashSet<>();

    private Instant createdAt;

    private Instant revokedAt;
}
