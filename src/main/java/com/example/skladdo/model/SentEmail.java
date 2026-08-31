package com.example.skladdo.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * An immutable audit record of one email sent to one partner - a client or a manufacturer. Never
 * editable or deletable once written (a send that failed at the SMTP level is still recorded, with
 * {@link SentEmailStatus#FAILED}).
 *
 * <p>The rendered subject/body are <em>snapshots</em> taken at send time - a later edit to the source
 * {@link EmailTemplate} never rewrites history, exactly as {@link Invoice} snapshots its buyer/line data
 * rather than holding live references. The {@link #manufacturer}/{@link #client} FK is kept for
 * navigation, but the name and address actually used are also snapshotted in case the partner is later
 * renamed or removed.</p>
 *
 * <p>{@code @TenantId}-scoped for normal (authenticated) access. The public tracking-pixel and inbound
 * webhook endpoints run with no tenant bound, so they must look a row up by {@link #trackingToken}
 * outside the tenant filter (see {@code TrackingService}/{@code InboundReplyService}) - which is why the
 * token is globally unique, not company-scoped.</p>
 */
@Entity
@Table(name = "sent_email", indexes = @Index(name = "idx_sent_email_batch", columnList = "company_id, batch_id"))
@Getter
@Setter
@EntityListeners(AuditingEntityListener.class)
public class SentEmail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    /**
     * Groups all recipient rows written by one bulk send, so the sent-emails list can present a batch as a
     * single item with aggregate open/reply counts. One random UUID per send call (a single-recipient send
     * is simply a batch of one). Null on rows written before batching existed - the aggregation query treats
     * a null batch as its own singleton batch keyed by row id.
     */
    @Column(name = "batch_id", length = 36, updatable = false)
    private String batchId;

    /**
     * Which side of the address book this went to, and therefore which of the two references below is
     * set. Never null on a row written since clients became reachable; older rows were all manufacturer
     * sends and are backfilled to MANUFACTURER by {@code SchemaMigrations}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_type", length = 16)
    private EmailRecipientType recipientType;

    /** Live reference to the recipient manufacturer (for navigation), or null for a client send. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manufacturer_id")
    private Manufacturer manufacturer;

    /** Live reference to the recipient client (for navigation), or null for a manufacturer send. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    private Client client;

    /** The partner name as it read at send time, whichever side it came from. */
    private String recipientNameSnapshot;

    /**
     * Id of whichever partner this went to, for the read side. Reads only the reference
     * {@link #recipientType} names, so a row is never asked for one it does not have. Derived, not a
     * column - Hibernate uses field access here, so this getter is invisible to it.
     */
    public Long getRecipientId() {
        if (recipientType == EmailRecipientType.CLIENT) {
            return client != null ? client.getId() : null;
        }
        return manufacturer != null ? manufacturer.getId() : null;
    }

    /** The address actually used at send time. */
    @Column(nullable = false)
    private String recipientEmail;

    /**
     * Id of the source template, if any; null when composed ad hoc. Stored as a plain id, not a live
     * FK: a sent email is an immutable audit record whose content is already snapshotted, and templates
     * must stay deletable - a foreign key would block deleting any template that had ever been used.
     */
    private Long templateId;

    @Column(nullable = false, length = 500)
    private String subjectSnapshot;

    /** Fully rendered HTML (tokens substituted, tracking pixel included). */
    @Column(nullable = false, length = 20000)
    private String bodySnapshot;

    /** Opaque random token used in the Reply-To local-part and the tracking-pixel URL. Globally unique. */
    @Column(nullable = false, unique = true, length = 64)
    private String trackingToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SentEmailStatus status;

    @Column(length = 2000)
    private String failureReason;

    /** Set on the first tracking-pixel hit; never overwritten (undercounts opens - image blocking). */
    private Instant viewedAt;

    /** Set when the first inbound reply is matched; the full reply list still grows in {@link EmailReply}. */
    private Instant repliedAt;

    /** Comma-separated names of the files attached to this email, for the audit trail. Null if none. */
    @Column(length = 2000)
    private String attachmentNames;

    @CreatedDate
    private Instant sentAt;

    /** The user who triggered the send - what per-user "emails sent" aggregation groups by. */
    @CreatedBy
    private Long sentById;
}
