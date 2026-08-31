package com.example.skladdo.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A send queued for later: everything needed to run one {@code EmailSendingService.sendNow} call at
 * {@link #scheduledAt}, held until a background dispatcher gets to it.
 *
 * <p>{@link #subject} and {@link #body} are stored <em>un-rendered</em>, tokens and all. That is the
 * point of scheduling rather than pre-rendering: {@code {{today}}} then resolves to the day the email
 * actually goes out, and a partner renamed between scheduling and sending is greeted by their current
 * name. Rendering happens once, at send time, exactly as it does for an immediate send.</p>
 *
 * <p>Short-lived by design - see {@link ScheduledEmailStatus}. The row is deleted once it has fired
 * (the {@link SentEmail} rows are the record) or once it is cancelled, so this table holds only work
 * outstanding or work that needs attention.</p>
 */
@Entity
@Table(name = "scheduled_email",
        indexes = @Index(name = "idx_scheduled_email_due", columnList = "status, scheduled_at"))
@Getter
@Setter
@EntityListeners(AuditingEntityListener.class)
public class ScheduledEmail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "company_id", updatable = false)
    private Long companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_type", nullable = false, length = 16)
    private EmailRecipientType recipientType;

    /**
     * Who it goes to, as plain ids of the side {@link #recipientType} names.
     *
     * <p>Ids rather than a mapped association: the partners are looked up fresh at send time anyway (so
     * the email reflects them as they are then, not as they were when it was scheduled), and a partner
     * deleted in the meantime should make one recipient fail rather than a foreign key block the delete.
     * Fetched eagerly because the dispatcher reads them on a background thread with no open session.</p>
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "scheduled_email_recipient",
            joinColumns = @JoinColumn(name = "scheduled_email_id"))
    @Column(name = "recipient_id", nullable = false)
    private Set<Long> recipientIds = new LinkedHashSet<>();

    /** Source template, recorded on the resulting sent emails. Null when composed ad hoc. */
    private Long templateId;

    /** The named person to address, for a single-recipient schedule. See {@code SendEmailRequest}. */
    private Long contactId;

    @NotBlank
    @Column(nullable = false, length = 500)
    private String subject;

    @NotBlank
    @Column(nullable = false, length = 10000)
    private String body;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ScheduledEmailStatus status = ScheduledEmailStatus.PENDING;

    /**
     * Why the whole send could not be attempted, as a translation key where one was available. Per-recipient
     * failures are not this - those are recorded on the {@link SentEmail} rows the send produced.
     */
    @Column(length = 2000)
    private String failureReason;

    /** See {@link ScheduledEmailAttachment}. A bag, not a set - order is the order they were picked. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "scheduled_email_attachment",
            joinColumns = @JoinColumn(name = "scheduled_email_id"))
    private List<ScheduledEmailAttachment> attachments = new ArrayList<>();

    @CreatedDate
    private Instant createdAt;

    /** Who scheduled it; the send is credited to them, not to the background thread. */
    @CreatedBy
    private Long createdById;
}
