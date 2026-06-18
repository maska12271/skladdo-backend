package com.example.tenderapp.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * One immutable row per status transition of a sales or purchase order, forming the status timeline
 * shown on the order detail page. {@code fromStatus} is null for the initial "order created" event.
 * {@code changedAt}/{@code changedById} are populated automatically by JPA auditing.
 */
@Entity
@Getter
@Setter
@EntityListeners(AuditingEntityListener.class)
public class OrderStatusChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderType orderType;

    @Column(nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus toStatus;

    @CreatedDate
    @Column(updatable = false)
    private Instant changedAt;

    @CreatedBy
    @Column(updatable = false)
    private Long changedById;
}
