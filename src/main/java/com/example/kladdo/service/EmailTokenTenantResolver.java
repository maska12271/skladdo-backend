package com.example.kladdo.service;

import com.example.kladdo.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Bridges the tenant gap for the two <em>unauthenticated</em> email endpoints (tracking pixel and
 * inbound reply webhook). Those requests never bind a tenant, so Hibernate's tenant filter would resolve
 * to company {@code -1} and match no rows - a JPA lookup by tracking token would silently find nothing.
 *
 * <p>This resolver first reads the owning {@code company_id} with a plain JDBC query (which bypasses the
 * tenant filter entirely), then runs the follow-up work with that tenant bound so ordinary repositories
 * behave correctly. The tenant is always cleared afterwards - the servlet thread is reused for unrelated
 * requests - mirroring the set/clear-in-finally pattern in {@code DataInitializer}.</p>
 */
@Component
public class EmailTokenTenantResolver {

    private final JdbcTemplate jdbc;

    public EmailTokenTenantResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The company that owns the sent email with this tracking token, or empty if the token is unknown. */
    public Optional<Long> companyIdForToken(String token) {
        List<Long> ids = jdbc.queryForList(
                "SELECT COMPANY_ID FROM SENT_EMAIL WHERE TRACKING_TOKEN = ?", Long.class, token);
        return ids.isEmpty() ? Optional.empty() : Optional.ofNullable(ids.get(0));
    }

    /** Runs {@code work} with {@code companyId} bound as the current tenant, always clearing afterwards. */
    public void withTenant(Long companyId, Runnable work) {
        TenantContext.setCompanyId(companyId);
        try {
            work.run();
        } finally {
            TenantContext.clear();
        }
    }
}
