package com.example.kladdo.model;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
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
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Setter
@EntityListeners(AuditingEntityListener.class)
public class PurchaseOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    @Column(unique = true)
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status = OrderStatus.NEW;

    private LocalDate orderDate;

    private LocalDate closingDate;

    private LocalDate expectedDeliveryDate;

    /**
     * ISO 4217 currency code this order's money amounts are in. Nullable at the DB level so the column
     * migrates onto existing rows ({@code ddl-auto=update}); a {@code null} value is treated as the company
     * base currency, and the service stamps the base currency on new orders.
     */
    @Column(length = 3)
    private String currency;

    /**
     * Exchange rate snapshotted when the order was saved: how many units of {@link #currency} one unit of
     * the company base currency buys ({@code 1 base = rate foreign}). The order's amounts are stored in
     * {@link #currency}; dividing by this rate gives the company-currency equivalent. Null/1 when the order
     * is already in the base currency.
     */
    @Column(precision = 19, scale = 6)
    private BigDecimal exchangeRate;

    /**
     * Optional tender this order belongs to. A plain id (not a mapped FK, like {@code SentEmail.templateId})
     * so the order survives the tender being deleted and serializes without a lazy association.
     */
    private Long tenderId;

    private String deliveryAddress;

    @Column(nullable = false)
    private BigDecimal deliveryPrice = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal subtotalAmount = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(length = 2000)
    private String notes;

    /**
     * Stored supplier invoice document for this order. {@code invoiceFileUrl} is an {@code /uploads/*}
     * path (the randomly-named stored file); {@code invoiceFileName} is the original filename, used for
     * display and as the download filename. Both null until a file is attached.
     */
    private String invoiceFileUrl;

    private String invoiceFileName;

    @ManyToOne
    @JoinColumn(nullable = false)
    private Manufacturer manufacturer;

    /**
     * Destination warehouse where received goods are stocked. Eagerly fetched (like
     * {@link #manufacturer}) so it serializes when the entity is returned directly with
     * {@code open-in-view=false}.
     */
    @ManyToOne
    @JoinColumn(name = "warehouse_id")
    private Warehouse warehouse;

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonManagedReference
    private List<PurchaseOrderItem> items = new ArrayList<>();

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