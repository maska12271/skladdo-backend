package com.example.skladdo.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * One inbound reply to a {@link SentEmail}. A manufacturer may reply more than once, so replies are
 * modelled as a list hanging off the sent email rather than a single field (the {@code repliedAt} flag on
 * {@link SentEmail} still gives a fast "has any reply" signal for list views).
 *
 * <p>No {@code @TenantId} of its own: tenant isolation is inherited through {@link #sentEmail} (the same
 * pattern as line-item entities under their parent). Every query reaches a reply via its sent email.</p>
 */
@Entity
@Getter
@Setter
@EntityListeners(AuditingEntityListener.class)
public class EmailReply {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sent_email_id", nullable = false)
    private SentEmail sentEmail;

    /** Mailgun's {@code stripped-text} (the reply with quoted history removed). */
    @Column(length = 5000)
    private String snippet;

    /** The manufacturer's replying address, for display. */
    private String fromAddress;

    @CreatedDate
    private Instant receivedAt;
}
