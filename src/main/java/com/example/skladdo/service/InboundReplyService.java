package com.example.skladdo.service;

import com.example.skladdo.model.EmailReply;
import com.example.skladdo.model.NotificationType;
import com.example.skladdo.repository.EmailReplyRepository;
import com.example.skladdo.repository.SentEmailRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Records inbound replies delivered by the Mailgun inbound-parse webhook. Like the tracking pixel, this
 * runs unauthenticated, so it resolves the tenant from the token before writing (see
 * {@link EmailTokenTenantResolver}). The webhook has already verified the request's HMAC signature.
 */
@Service
public class InboundReplyService {

    private static final Logger log = LoggerFactory.getLogger(InboundReplyService.class);
    private static final int MAX_SNIPPET_LENGTH = 5000;

    private final SentEmailRepository sentEmailRepository;
    private final EmailReplyRepository emailReplyRepository;
    private final EmailTokenTenantResolver tenantResolver;
    private final NotificationService notificationService;

    public InboundReplyService(SentEmailRepository sentEmailRepository,
                               EmailReplyRepository emailReplyRepository,
                               EmailTokenTenantResolver tenantResolver,
                               NotificationService notificationService) {
        this.sentEmailRepository = sentEmailRepository;
        this.emailReplyRepository = emailReplyRepository;
        this.tenantResolver = tenantResolver;
        this.notificationService = notificationService;
    }

    /**
     * Stores a reply against the sent email identified by {@code token}. An unknown token is logged and
     * ignored (nothing actionable) - the webhook still returns 200 so Mailgun does not retry.
     */
    public void recordReply(String token, String fromAddress, String snippet) {
        tenantResolver.companyIdForToken(token).ifPresentOrElse(companyId ->
                tenantResolver.withTenant(companyId, () ->
                        sentEmailRepository.findByTrackingToken(token).ifPresent(email -> {
                            EmailReply reply = new EmailReply();
                            reply.setSentEmail(email);
                            reply.setFromAddress(fromAddress);
                            reply.setSnippet(truncate(snippet));
                            emailReplyRepository.save(reply);

                            if (email.getRepliedAt() == null) {
                                email.setRepliedAt(Instant.now());
                                sentEmailRepository.save(email);
                            }

                            // Tell whoever sent the original email. Dedupe is per sent-email, so a
                            // manufacturer replying repeatedly on one thread only notifies once.
                            if (email.getSentById() != null) {
                                notificationService.notifyUserById(
                                        email.getSentById(),
                                        NotificationType.EMAIL_REPLY,
                                        email.getManufacturerNameSnapshot() != null
                                                ? email.getManufacturerNameSnapshot()
                                                : email.getRecipientEmail(),
                                        email.getBatchId() != null ? "/emails/" + email.getBatchId() : "/emails",
                                        "EMAIL_REPLY:" + email.getId());
                            }
                        })),
                () -> log.warn("Inbound reply for unknown tracking token (ignored)"));
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_SNIPPET_LENGTH ? value : value.substring(0, MAX_SNIPPET_LENGTH);
    }
}
