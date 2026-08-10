package com.example.kladdo.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;

/**
 * One immutable row per noteworthy change made inside a company - the company-wide "who did what, when"
 * trail. {@code @TenantId}-scoped, so a company only ever sees its own history.
 *
 * <p>Rows are written explicitly by {@code AuditService} from the service methods worth recording, rather
 * than by a blanket Hibernate interceptor: an audit line is only useful if it is deliberate, and explicit
 * calls keep what is recorded obvious at the call site.</p>
 *
 * <p>This deliberately overlaps {@link OrderStatusChange}, which stays the source of the per-order status
 * timeline on the order page. {@link OrderStatusChange} answers "what happened to this order"; the audit
 * log answers "what happened in this company" across every entity.</p>
 *
 * <p>Nothing updates or deletes a row here, so there are no auditing timestamps beyond
 * {@link #createdAt} - which is set on write rather than by {@code @CreatedDate}, since the row is created
 * once and never touched again.</p>
 */
@Entity
@Getter
@Setter
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AuditAction action;

    /**
     * Which kind of record was touched, e.g. {@code "USER"} or {@code "SALES_ORDER"}. A plain string
     * rather than an enum on purpose: auditing a new kind of record should not require widening a native
     * {@code ENUM} column. The values in use are the {@code ENTITY_*} constants on {@code AuditService}.
     */
    @Column(nullable = false, updatable = false, length = 40)
    private String entityType;

    /** Id of the touched record, so the viewer can link to it. Null when the change has no single row. */
    @Column(updatable = false)
    private Long entityId;

    /**
     * Language-neutral detail identifying the affected record - an email, an order number, a
     * {@code "PENDING -> SHIPPED"} transition. Deliberately not a translated sentence: the row may be read
     * in a different language than it was written in, so the viewer renders the translated action and
     * entity type and shows this as the supporting detail. Null when there is nothing useful to add.
     */
    @Column(updatable = false, length = 500)
    private String details;

    /** Who made the change. Null for a change with no signed-in user behind it (e.g. a background job). */
    @Column(updatable = false)
    private Long actorUserId;

    /** The actor's name/email captured at write time, so the row still reads after that account is gone. */
    @Column(updatable = false, length = 255)
    private String actorName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
