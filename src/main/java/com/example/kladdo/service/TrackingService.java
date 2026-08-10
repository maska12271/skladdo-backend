package com.example.kladdo.service;

import com.example.kladdo.repository.SentEmailRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Records email-open signals from the tracking pixel. Runs for unauthenticated pixel requests, so it
 * resolves the tenant from the token before touching the audit row (see {@link EmailTokenTenantResolver}).
 *
 * <p>Open tracking is best-effort: many mail clients block remote images, so a missing {@code viewedAt}
 * does not mean the email was not read. It is a signal, not proof.</p>
 */
@Service
public class TrackingService {

    private final SentEmailRepository sentEmailRepository;
    private final EmailTokenTenantResolver tenantResolver;

    public TrackingService(SentEmailRepository sentEmailRepository,
                           EmailTokenTenantResolver tenantResolver) {
        this.sentEmailRepository = sentEmailRepository;
        this.tenantResolver = tenantResolver;
    }

    /** Marks the first open for the given tracking token. No-op for an unknown token or a repeat open. */
    public void recordView(String token) {
        tenantResolver.companyIdForToken(token).ifPresent(companyId ->
                tenantResolver.withTenant(companyId, () ->
                        sentEmailRepository.findByTrackingToken(token).ifPresent(email -> {
                            if (email.getViewedAt() == null) {
                                email.setViewedAt(Instant.now());
                                sentEmailRepository.save(email);
                            }
                        })));
    }
}
